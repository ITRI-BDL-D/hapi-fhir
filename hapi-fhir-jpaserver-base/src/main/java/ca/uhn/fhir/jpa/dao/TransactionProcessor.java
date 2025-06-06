/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.dao;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.api.svc.ResolveIdentityMode;
import ca.uhn.fhir.jpa.config.HapiFhirHibernateJpaDialect;
import ca.uhn.fhir.jpa.model.cross.IResourceLookup;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamToken;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.entity.StorageSettings;
import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.search.ResourceSearchUrlSvc;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.util.MemoryCacheService;
import ca.uhn.fhir.jpa.util.QueryChunker;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.IResourcePersistentId;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.util.ResourceReferenceInfo;
import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.TaskChunker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import jakarta.annotation.Nonnull;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceContextType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.apache.commons.lang3.Validate;
import org.hibernate.internal.SessionImpl;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ca.uhn.fhir.util.UrlUtil.determineResourceTypeInResourceUrl;
import static org.apache.commons.lang3.StringUtils.countMatches;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class TransactionProcessor extends BaseTransactionProcessor {

	/**
	 * Matches conditional URLs in the form of [resourceType]?[paramName]=[paramValue]{...more params...}
	 */
	public static final Pattern MATCH_URL_PATTERN = Pattern.compile("^[^?]++[?][a-z0-9-]+=[^&,]++");

	public static final int CONDITIONAL_URL_FETCH_CHUNK_SIZE = 100;
	private static final Logger ourLog = LoggerFactory.getLogger(TransactionProcessor.class);

	@Autowired
	private ApplicationContext myApplicationContext;

	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;

	@Autowired(required = false)
	private HapiFhirHibernateJpaDialect myHapiFhirHibernateJpaDialect;

	@Autowired
	private IIdHelperService<JpaPid> myIdHelperService;

	@Autowired
	private JpaStorageSettings myStorageSettings;

	@Autowired
	private FhirContext myFhirContext;

	@Autowired
	private MatchResourceUrlService<JpaPid> myMatchResourceUrlService;

	@Autowired
	private MatchUrlService myMatchUrlService;

	@Autowired
	private ResourceSearchUrlSvc myResourceSearchUrlSvc;

	@Autowired
	private MemoryCacheService myMemoryCacheService;

	@Autowired
	private IRequestPartitionHelperSvc myRequestPartitionHelperSvc;

	public void setEntityManagerForUnitTest(EntityManager theEntityManager) {
		myEntityManager = theEntityManager;
	}

	@Override
	protected void validateDependencies() {
		super.validateDependencies();

		Validate.notNull(myEntityManager, "EntityManager must not be null");
	}

	@VisibleForTesting
	public void setFhirContextForUnitTest(FhirContext theFhirContext) {
		myFhirContext = theFhirContext;
	}

	@Override
	public void setStorageSettings(StorageSettings theStorageSettings) {
		myStorageSettings = (JpaStorageSettings) theStorageSettings;
		super.setStorageSettings(theStorageSettings);
	}

	@Override
	protected EntriesToProcessMap doTransactionWriteOperations(
			final RequestDetails theRequest,
			String theActionName,
			TransactionDetails theTransactionDetails,
			Set<IIdType> theAllIds,
			IdSubstitutionMap theIdSubstitutions,
			Map<IIdType, DaoMethodOutcome> theIdToPersistedOutcome,
			IBaseBundle theResponse,
			IdentityHashMap<IBase, Integer> theOriginalRequestOrder,
			List<IBase> theEntries,
			StopWatch theTransactionStopWatch) {

		/*
		 * We temporarily set the flush mode for the duration of the DB transaction
		 * from the default of AUTO to the temporary value of COMMIT here. We do this
		 * because in AUTO mode, if any SQL SELECTs are required during the
		 * processing of an individual transaction entry, the server will flush the
		 * pending INSERTs/UPDATEs to the database before executing the SELECT.
		 * This hurts performance since we don't get the benefit of batching those
		 * write operations as much as possible. The tradeoff here is that we
		 * could theoretically have transaction operations which try to read
		 * data previously written in the same transaction, and they won't see it.
		 * This shouldn't actually be an issue anyhow - we pre-fetch conditional
		 * URLs and reference targets at the start of the transaction. But this
		 * tradeoff still feels worth it, since the most common use of transactions
		 * is for fast writing of data.
		 *
		 * Note that it's probably not necessary to reset it back, it should
		 * automatically go back to the default value after the transaction, but
		 * we reset it just to be safe.
		 */
		FlushModeType initialFlushMode = myEntityManager.getFlushMode();
		try {
			myEntityManager.setFlushMode(FlushModeType.COMMIT);

			ITransactionProcessorVersionAdapter<?, ?> versionAdapter = getVersionAdapter();
			RequestPartitionId requestPartitionId =
					super.determineRequestPartitionIdForWriteEntries(theRequest, theEntries);

			if (requestPartitionId != null) {
				preFetch(theRequest, theTransactionDetails, theEntries, versionAdapter, requestPartitionId);
			}

			return super.doTransactionWriteOperations(
					theRequest,
					theActionName,
					theTransactionDetails,
					theAllIds,
					theIdSubstitutions,
					theIdToPersistedOutcome,
					theResponse,
					theOriginalRequestOrder,
					theEntries,
					theTransactionStopWatch);
		} finally {
			myEntityManager.setFlushMode(initialFlushMode);
		}
	}

	@SuppressWarnings("rawtypes")
	private void preFetch(
			RequestDetails theRequestDetails,
			TransactionDetails theTransactionDetails,
			List<IBase> theEntries,
			ITransactionProcessorVersionAdapter theVersionAdapter,
			RequestPartitionId theRequestPartitionId) {
		Set<String> foundIds = new HashSet<>();
		Set<JpaPid> idsToPreFetchBodiesFor = new HashSet<>();
		Set<JpaPid> idsToPreFetchVersionsFor = new HashSet<>();

		/*
		 * Pre-Fetch any resources that are referred to normally by ID, e.g.
		 * regular FHIR updates within the transaction.
		 */
		preFetchResourcesById(
				theTransactionDetails,
				theEntries,
				theVersionAdapter,
				theRequestPartitionId,
				foundIds,
				idsToPreFetchBodiesFor);

		/*
		 * Pre-resolve any conditional URLs we can
		 */
		preFetchConditionalUrls(
				theRequestDetails,
				theTransactionDetails,
				theEntries,
				theVersionAdapter,
				theRequestPartitionId,
				idsToPreFetchBodiesFor,
				idsToPreFetchVersionsFor);

		/*
		 * Pre-Fetch Resource Bodies (this will happen for any resources we are potentially
		 * going to update)
		 */
		IFhirSystemDao<?, ?> systemDao = myApplicationContext.getBean(IFhirSystemDao.class);
		systemDao.preFetchResources(List.copyOf(idsToPreFetchBodiesFor), true);

		/*
		 * Pre-Fetch Resource Versions (this will happen for any resources we are doing a
		 * conditional create on, meaning we don't actually care about the contents, just
		 * the ID and version)
		 */
		preFetchResourceVersions(idsToPreFetchVersionsFor);
	}

	/**
	 * Given a collection of {@link JpaPid}, loads the current version associated with
	 * each PID and puts it into the {@link JpaPid#setVersion(Long)} field.
	 */
	private void preFetchResourceVersions(Set<JpaPid> theIds) {
		ourLog.trace("Versions to fetch: {}", theIds);

		for (Iterator<JpaPid> it = theIds.iterator(); it.hasNext(); ) {
			JpaPid pid = it.next();
			Long version = myMemoryCacheService.getIfPresent(
					MemoryCacheService.CacheEnum.RESOURCE_CONDITIONAL_CREATE_VERSION, pid);
			if (version != null) {
				it.remove();
				pid.setVersion(version);
			}
		}

		if (!theIds.isEmpty()) {
			Map<JpaPid, JpaPid> idMap = theIds.stream().collect(Collectors.toMap(t -> t, t -> t));

			QueryChunker.chunk(theIds, ids -> {
				CriteriaBuilder cb = myEntityManager.getCriteriaBuilder();
				CriteriaQuery<Tuple> cq = cb.createTupleQuery();
				Root<ResourceTable> from = cq.from(ResourceTable.class);
				cq.multiselect(from.get("myPid"), from.get("myVersion"));
				cq.where(from.get("myPid").in(ids));
				TypedQuery<Tuple> query = myEntityManager.createQuery(cq);
				List<Tuple> results = query.getResultList();

				for (Tuple tuple : results) {
					JpaPid pid = tuple.get(0, JpaPid.class);
					Long version = tuple.get(1, Long.class);
					idMap.get(pid).setVersion(version);

					myMemoryCacheService.putAfterCommit(
							MemoryCacheService.CacheEnum.RESOURCE_CONDITIONAL_CREATE_VERSION, pid, version);
				}
			});
		}
	}

	@Override
	@SuppressWarnings("rawtypes")
	protected void postTransactionProcess(TransactionDetails theTransactionDetails) {
		Set<IResourcePersistentId> resourceIds = theTransactionDetails.getUpdatedResourceIds();
		if (resourceIds != null && !resourceIds.isEmpty()) {
			List<JpaPid> ids = resourceIds.stream().map(r -> (JpaPid) r).collect(Collectors.toList());
			myResourceSearchUrlSvc.deleteByResIds(ids);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void preFetchResourcesById(
			TransactionDetails theTransactionDetails,
			List<IBase> theEntries,
			ITransactionProcessorVersionAdapter theVersionAdapter,
			RequestPartitionId theRequestPartitionId,
			Set<String> foundIds,
			Set<JpaPid> theIdsToPreFetchBodiesFor) {

		FhirTerser terser = myFhirContext.newTerser();

		enum PrefetchReasonEnum {
			/**
			 * The ID is being prefetched because it is the ID in a resource reference
			 * within a resource being updated. In this case, we care whether the resource
			 * is deleted (since you can't reference a deleted resource), but we don't
			 * need to fetch the body since we don't actually care about its contents.
			 */
			REFERENCE_TARGET,
			/**
			 * The ID is being prefetched because it is the ID of a resource being
			 * updated directly by the transaction. In this case we don't care if it's
			 * deleted (since it's fine to update a deleted resource), and we do need
			 * to prefetch the current body so we can tell how it has changed.
			 */
			DIRECT_TARGET
		}
		Map<IIdType, PrefetchReasonEnum> idsToPreResolve = new HashMap<>(theEntries.size() * 3);

		for (IBase nextEntry : theEntries) {
			IBaseResource resource = theVersionAdapter.getResource(nextEntry);
			if (resource != null) {
				String verb = theVersionAdapter.getEntryRequestVerb(myFhirContext, nextEntry);

				/*
				 * Pre-fetch any resources that are being updated or patched within
				 * the transaction
				 */
				if ("PUT".equals(verb) || "PATCH".equals(verb)) {
					String requestUrl = theVersionAdapter.getEntryRequestUrl(nextEntry);
					if (countMatches(requestUrl, '?') == 0) {
						IIdType id = myFhirContext.getVersion().newIdType();
						id.setValue(requestUrl);
						IIdType unqualifiedVersionless = id.toUnqualifiedVersionless();
						idsToPreResolve.put(unqualifiedVersionless, PrefetchReasonEnum.DIRECT_TARGET);
					}
				}

				/*
				 * If there are any resource references anywhere in any resources being
				 * created or updated that point to another target resource directly by
				 * ID, we also want to prefetch the identity of that target ID
				 */
				if ("PUT".equals(verb) || "POST".equals(verb)) {
					for (ResourceReferenceInfo referenceInfo : terser.getAllResourceReferences(resource)) {
						IIdType reference = referenceInfo.getResourceReference().getReferenceElement();
						if (reference != null
								&& !reference.isLocal()
								&& !reference.isUuid()
								&& reference.hasResourceType()
								&& reference.hasIdPart()
								&& !reference.getValue().contains("?")) {

							// We use putIfAbsent here because if we're already fetching
							// as a direct target we don't want to downgrade to just a
							// reference target
							idsToPreResolve.putIfAbsent(
									reference.toUnqualifiedVersionless(), PrefetchReasonEnum.REFERENCE_TARGET);
						}
					}
				}
			}
		}

		/*
		 * If any of the entries in the pre-fetch ID map have a value of REFERENCE_TARGET,
		 * this means we can't rely on cached identities because we need to know the
		 * current deleted status of at least one of them. This is because another thread
		 * (or potentially even another process elsewhere) could have moved the resource
		 * to "deleted", and we can't allow someone to add a reference to a deleted
		 * resource. If deletes are disabled on this server though, we can trust that
		 * nothing has been moved to "deleted" status since it was put in the cache, and
		 * it's safe to use the cache.
		 *
		 * On the other hand, if all resource IDs we want to prefetch have a value of
		 * DIRECT_UPDATE, that means these IDs are all resources we're about to
		 * modify. In that case it doesn't even matter if the resource is currently
		 * deleted because we're going to resurrect it in that case.
		 */
		boolean preFetchIncludesReferences =
				idsToPreResolve.values().stream().anyMatch(t -> t == PrefetchReasonEnum.REFERENCE_TARGET);
		ResolveIdentityMode resolveMode = preFetchIncludesReferences
				? ResolveIdentityMode.includeDeleted().noCacheUnlessDeletesDisabled()
				: ResolveIdentityMode.includeDeleted().cacheOk();

		Map<IIdType, IResourceLookup<JpaPid>> outcomes = myIdHelperService.resolveResourceIdentities(
				theRequestPartitionId, idsToPreResolve.keySet(), resolveMode);
		for (Iterator<Map.Entry<IIdType, IResourceLookup<JpaPid>>> iterator =
						outcomes.entrySet().iterator();
				iterator.hasNext(); ) {
			Map.Entry<IIdType, IResourceLookup<JpaPid>> entry = iterator.next();
			JpaPid next = entry.getValue().getPersistentId();
			IIdType unqualifiedVersionlessId = entry.getKey();
			switch (idsToPreResolve.get(unqualifiedVersionlessId)) {
				case DIRECT_TARGET -> {
					if (myStorageSettings.getResourceClientIdStrategy() != JpaStorageSettings.ClientIdStrategyEnum.ANY
							|| (next.getAssociatedResourceId() != null
									&& !next.getAssociatedResourceId().isIdPartValidLong())) {
						theIdsToPreFetchBodiesFor.add(next);
					}
				}
				case REFERENCE_TARGET -> {
					if (entry.getValue().getDeleted() != null) {
						iterator.remove();
						continue;
					}
				}
			}

			foundIds.add(unqualifiedVersionlessId.getValue());
			theTransactionDetails.addResolvedResourceId(unqualifiedVersionlessId, next);
		}

		// Any IDs that could not be resolved are presumably not there, so
		// cache that fact so we don't look again later
		for (IIdType next : idsToPreResolve.keySet()) {
			if (!foundIds.contains(next.getValue())) {
				theTransactionDetails.addResolvedResourceId(next.toUnqualifiedVersionless(), null);
			}
		}
	}

	@Override
	protected void handleVerbChangeInTransactionWriteOperations() {
		super.handleVerbChangeInTransactionWriteOperations();

		myEntityManager.flush();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void preFetchConditionalUrls(
			RequestDetails theRequestDetails,
			TransactionDetails theTransactionDetails,
			List<IBase> theEntries,
			ITransactionProcessorVersionAdapter theVersionAdapter,
			RequestPartitionId theRequestPartitionId,
			Set<JpaPid> theIdsToPreFetchBodiesFor,
			Set<JpaPid> theIdsToPreFetchVersionsFor) {

		List<MatchUrlToResolve> searchParameterMapsToResolve = new ArrayList<>();
		for (IBase nextEntry : theEntries) {
			IBaseResource resource = theVersionAdapter.getResource(nextEntry);
			if (resource != null) {
				String verb = theVersionAdapter.getEntryRequestVerb(myFhirContext, nextEntry);
				String requestUrl = theVersionAdapter.getEntryRequestUrl(nextEntry);
				String requestIfNoneExist = theVersionAdapter.getEntryIfNoneExist(nextEntry);
				String resourceType = determineResourceTypeInResourceUrl(myFhirContext, requestUrl);
				if (resourceType == null) {
					resourceType = myFhirContext.getResourceType(resource);
				}
				if (("PUT".equals(verb) || "PATCH".equals(verb)) && requestUrl != null && requestUrl.contains("?")) {
					processConditionalUrlForPreFetching(
							theRequestPartitionId,
							resourceType,
							requestUrl,
							true,
							false,
							theIdsToPreFetchBodiesFor,
							searchParameterMapsToResolve);
				} else if ("POST".equals(verb) && requestIfNoneExist != null && requestIfNoneExist.contains("?")) {
					processConditionalUrlForPreFetching(
							theRequestPartitionId,
							resourceType,
							requestIfNoneExist,
							false,
							true,
							theIdsToPreFetchBodiesFor,
							searchParameterMapsToResolve);
				}

				if (myStorageSettings.isAllowInlineMatchUrlReferences()) {
					List<ResourceReferenceInfo> references =
							myFhirContext.newTerser().getAllResourceReferences(resource);
					for (ResourceReferenceInfo next : references) {
						String referenceUrl = next.getResourceReference()
								.getReferenceElement()
								.getValue();
						String refResourceType = determineResourceTypeInResourceUrl(myFhirContext, referenceUrl);
						if (refResourceType != null) {
							processConditionalUrlForPreFetching(
									theRequestPartitionId,
									refResourceType,
									referenceUrl,
									false,
									false,
									theIdsToPreFetchBodiesFor,
									searchParameterMapsToResolve);
						}
					}
				}
			}
		}

		TaskChunker.chunk(
				searchParameterMapsToResolve,
				CONDITIONAL_URL_FETCH_CHUNK_SIZE,
				map -> preFetchSearchParameterMaps(
						theRequestDetails,
						theTransactionDetails,
						theRequestPartitionId,
						map,
						theIdsToPreFetchBodiesFor,
						theIdsToPreFetchVersionsFor));
	}

	/**
	 * This method attempts to resolve a collection of conditional URLs that were found
	 * in a FHIR transaction bundle being processed.
	 *
	 * @param theRequestDetails              The active request
	 * @param theTransactionDetails          The active transaction details
	 * @param theRequestPartitionId          The active partition
	 * @param theInputParameters             These are the conditional URLs that will actually be resolved
	 * @param theOutputPidsToLoadBodiesFor   This list will be added to with any resource PIDs that need to be fully
	 *                                       preloaded (i.e. fetch the actual resource body since we're presumably
	 *                                       going to update it and will need to see its current state eventually)
	 * @param theOutputPidsToLoadVersionsFor This list will be added to with any resource PIDs that need to have
	 *                                       their current version resolved. This is used for conditional creates,
	 *                                       where we don't actually care about the body of the resource, only
	 *                                       the version it has (since the version is returned in the response,
	 *                                       and potentially used if we're auto-versioning references).
	 */
	@VisibleForTesting
	public void preFetchSearchParameterMaps(
			RequestDetails theRequestDetails,
			TransactionDetails theTransactionDetails,
			RequestPartitionId theRequestPartitionId,
			List<MatchUrlToResolve> theInputParameters,
			Set<JpaPid> theOutputPidsToLoadBodiesFor,
			Set<JpaPid> theOutputPidsToLoadVersionsFor) {

		Set<Long> systemAndValueHashes = new HashSet<>();
		Set<Long> valueHashes = new HashSet<>();

		for (MatchUrlToResolve next : theInputParameters) {
			Collection<List<List<IQueryParameterType>>> values = next.myMatchUrlSearchMap.values();

			/*
			 * Any conditional URLs that consist of a single token parameter are batched
			 * up into a single query against the HFJ_SPIDX_TOKEN table so that we only
			 * perform one SQL query for all of them.
			 *
			 * We could potentially add other patterns in the future, but it's much more
			 * tricky to implement this when there are multiple parameters, and non-token
			 * parameter types aren't often used on their own in conditional URLs. So for
			 * now we handle single-token only, and that's probably good enough.
			 */
			boolean canBeHandledInAggregateQuery = false;

			if (values.size() == 1) {
				List<List<IQueryParameterType>> andList = values.iterator().next();
				IQueryParameterType param = andList.get(0).get(0);

				if (param instanceof TokenParam tokenParam) {
					canBeHandledInAggregateQuery = buildHashPredicateFromTokenParam(
							tokenParam, theRequestPartitionId, next, systemAndValueHashes, valueHashes);
				}
			}

			if (!canBeHandledInAggregateQuery) {
				Set<JpaPid> matchUrlResults = myMatchResourceUrlService.processMatchUrl(
						next.myRequestUrl,
						next.myResourceDefinition.getImplementingClass(),
						theTransactionDetails,
						theRequestDetails,
						theRequestPartitionId);
				for (JpaPid matchUrlResult : matchUrlResults) {
					handleFoundPreFetchResourceId(
							theTransactionDetails,
							theOutputPidsToLoadBodiesFor,
							theOutputPidsToLoadVersionsFor,
							next,
							matchUrlResult);
				}
			}
		}

		preFetchSearchParameterMapsToken(
				"myHashSystemAndValue",
				systemAndValueHashes,
				theTransactionDetails,
				theRequestPartitionId,
				theInputParameters,
				theOutputPidsToLoadBodiesFor,
				theOutputPidsToLoadVersionsFor);
		preFetchSearchParameterMapsToken(
				"myHashValue",
				valueHashes,
				theTransactionDetails,
				theRequestPartitionId,
				theInputParameters,
				theOutputPidsToLoadBodiesFor,
				theOutputPidsToLoadVersionsFor);

		// For each SP Map which did not return a result, tag it as not found.
		theInputParameters.stream()
				// No matches
				.filter(match -> !match.myResolved)
				.forEach(match -> {
					ourLog.debug("Was unable to match url {} from database", match.myRequestUrl);
					theTransactionDetails.addResolvedMatchUrl(
							myFhirContext, match.myRequestUrl, TransactionDetails.NOT_FOUND);
				});
	}

	/**
	 * Here we do a select against the {@link ResourceIndexedSearchParamToken} table for any rows that have the
	 * specific sys+val or val hashes we know we need to pre-fetch.
	 * <p>
	 * Note that we do a tuple query for only 2 columns in order to ensure that we can get by with only
	 * the data in the index (ie no need to load the actual table rows).
	 */
	public void preFetchSearchParameterMapsToken(
			String theIndexColumnName,
			Set<Long> theHashesForIndexColumn,
			TransactionDetails theTransactionDetails,
			RequestPartitionId theRequestPartitionId,
			List<MatchUrlToResolve> theInputParameters,
			Set<JpaPid> theOutputPidsToLoadFully,
			Set<JpaPid> theOutputPidsToLoadVersionsFor) {
		if (!theHashesForIndexColumn.isEmpty()) {
			ListMultimap<Long, MatchUrlToResolve> hashToSearchMap =
					buildHashToSearchMap(theInputParameters, theIndexColumnName);
			CriteriaBuilder cb = myEntityManager.getCriteriaBuilder();
			CriteriaQuery<Tuple> cq = cb.createTupleQuery();
			Root<ResourceIndexedSearchParamToken> from = cq.from(ResourceIndexedSearchParamToken.class);
			cq.multiselect(from.get("myPartitionIdValue"), from.get("myResourcePid"), from.get(theIndexColumnName));

			Predicate masterPredicate;
			if (theHashesForIndexColumn.size() == 1) {
				masterPredicate = cb.equal(
						from.get(theIndexColumnName),
						theHashesForIndexColumn.iterator().next());
			} else {
				masterPredicate = from.get(theIndexColumnName).in(theHashesForIndexColumn);
			}

			if (myPartitionSettings.isPartitioningEnabled()
					&& !myPartitionSettings.isIncludePartitionInSearchHashes()) {
				if (myRequestPartitionHelperSvc.isDefaultPartition(theRequestPartitionId)
						&& myPartitionSettings.getDefaultPartitionId() == null) {
					Predicate partitionIdCriteria = cb.isNull(from.get("myPartitionIdValue"));
					masterPredicate = cb.and(partitionIdCriteria, masterPredicate);
				} else if (!theRequestPartitionId.isAllPartitions()) {
					Predicate partitionIdCriteria =
							from.get("myPartitionIdValue").in(theRequestPartitionId.getPartitionIds());
					masterPredicate = cb.and(partitionIdCriteria, masterPredicate);
				}
			}

			cq.where(masterPredicate);

			TypedQuery<Tuple> query = myEntityManager.createQuery(cq);

			/*
			 * If we have 10 unique conditional URLs we're resolving, each one should
			 * resolve to 0..1 resources if they are valid as conditional URLs. So we would
			 * expect this query to return 0..10 rows, since conditional URLs for all
			 * conditional operations except DELETE (which isn't being applied here) are
			 * only allowed to resolve to 0..1 resources.
			 *
			 * If a conditional URL matches 2+ resources that is an error, and we'll
			 * be throwing an exception below. This limit is here for safety just to
			 * ensure that if someone uses a conditional URL that matches a million resources,
			 * we don't do a super-expensive fetch.
			 */
			query.setMaxResults(theHashesForIndexColumn.size() + 1);

			List<Tuple> results = query.getResultList();

			for (Tuple nextResult : results) {
				Integer nextPartitionId = nextResult.get(0, Integer.class);
				Long nextResourcePid = nextResult.get(1, Long.class);
				Long nextHash = nextResult.get(2, Long.class);

				List<MatchUrlToResolve> matchedSearch = hashToSearchMap.get(nextHash);
				matchedSearch.forEach(matchUrl -> {
					ourLog.debug("Matched url {} from database", matchUrl.myRequestUrl);
					JpaPid pid = JpaPid.fromId(nextResourcePid, nextPartitionId);
					handleFoundPreFetchResourceId(
							theTransactionDetails,
							theOutputPidsToLoadFully,
							theOutputPidsToLoadVersionsFor,
							matchUrl,
							pid);
				});
			}
		}
	}

	private void handleFoundPreFetchResourceId(
			TransactionDetails theTransactionDetails,
			Set<JpaPid> theOutputPidsToLoadFully,
			Set<JpaPid> theOutputPidsToLoadVersionsFor,
			MatchUrlToResolve theMatchUrl,
			JpaPid theFoundPid) {
		if (theMatchUrl.myShouldPreFetchResourceBody) {
			theOutputPidsToLoadFully.add(theFoundPid);
		}
		if (theMatchUrl.myShouldPreFetchResourceVersion) {
			theOutputPidsToLoadVersionsFor.add(theFoundPid);
		}
		myMatchResourceUrlService.matchUrlResolved(
				theTransactionDetails,
				theMatchUrl.myResourceDefinition.getName(),
				theMatchUrl.myRequestUrl,
				theFoundPid);
		theTransactionDetails.addResolvedMatchUrl(myFhirContext, theMatchUrl.myRequestUrl, theFoundPid);
		theMatchUrl.setResolved(true);
	}

	/**
	 * Examines a conditional URL, and potentially adds it to either {@literal theOutputIdsToPreFetchBodiesFor}
	 * or {@literal theOutputSearchParameterMapsToResolve}.
	 * <p>
	 * Note that if {@literal theShouldPreFetchResourceBody} is false, then we'll check if a given match
	 * URL resolves to a resource PID, but we won't actually try to load that resource. If we're resolving
	 * a match URL because it's there for a conditional update, we'll eagerly fetch the
	 * actual resource because we need to know its current state in order to update it. However, if
	 * the match URL is from an inline match URL in a resource body, we really only care about
	 * the PID and don't need the body so we don't load it. This does have a security implication, since
	 * it means that the {@link ca.uhn.fhir.interceptor.api.Pointcut#STORAGE_PRESHOW_RESOURCES} pointcut
	 * isn't fired even though the user has resolved the URL (meaning they may be able to test for
	 * the existence of a resource using a match URL). There is a test for this called
	 * {@literal testTransactionCreateInlineMatchUrlWithAuthorizationDenied()}. This security tradeoff
	 * is acceptable since we're only prefetching things with very simple match URLs (nothing with
	 * a reference in it for example) so it's not really possible to doing anything useful with this.
	 * </p>
	 *
	 * @param thePartitionId                        The partition ID of the associated resource (can be null)
	 * @param theResourceType                       The resource type associated with the match URL (ie what resource type should it resolve to)
	 * @param theRequestUrl                         The actual match URL, which could be as simple as just parameters or could include the resource type too
	 * @param theShouldPreFetchResourceBody         Should we also fetch the actual resource body, or just figure out the PID associated with it? See the method javadoc above for some context.
	 * @param theOutputIdsToPreFetchBodiesFor       This will be populated with any resource PIDs that need to be pre-fetched
	 * @param theOutputSearchParameterMapsToResolve This will be populated with any {@link SearchParameterMap} instances corresponding to match URLs we need to resolve
	 */
	private void processConditionalUrlForPreFetching(
			RequestPartitionId thePartitionId,
			String theResourceType,
			String theRequestUrl,
			boolean theShouldPreFetchResourceBody,
			boolean theShouldPreFetchResourceVersion,
			Set<JpaPid> theOutputIdsToPreFetchBodiesFor,
			List<MatchUrlToResolve> theOutputSearchParameterMapsToResolve) {
		JpaPid cachedId =
				myMatchResourceUrlService.processMatchUrlUsingCacheOnly(theResourceType, theRequestUrl, thePartitionId);
		if (cachedId != null) {
			if (theShouldPreFetchResourceBody) {
				theOutputIdsToPreFetchBodiesFor.add(cachedId);
			}
		} else if (MATCH_URL_PATTERN.matcher(theRequestUrl).find()) {
			RuntimeResourceDefinition resourceDefinition = myFhirContext.getResourceDefinition(theResourceType);
			SearchParameterMap matchUrlSearchMap =
					myMatchUrlService.translateMatchUrl(theRequestUrl, resourceDefinition);
			theOutputSearchParameterMapsToResolve.add(new MatchUrlToResolve(
					theRequestUrl,
					matchUrlSearchMap,
					resourceDefinition,
					theShouldPreFetchResourceBody,
					theShouldPreFetchResourceVersion));
		}
	}

	/**
	 * Given a token parameter, build the query predicate based on its hash. Uses system and value if both are available, otherwise just value.
	 * If neither are available, it returns null.
	 *
	 * @return Returns {@literal true} if the param was added to one of the output lists
	 */
	private boolean buildHashPredicateFromTokenParam(
			TokenParam theTokenParam,
			RequestPartitionId theRequestPartitionId,
			MatchUrlToResolve theMatchUrl,
			Set<Long> theOutputSysAndValuePredicates,
			Set<Long> theOutputValuePredicates) {
		if (isNotBlank(theTokenParam.getValue()) && isNotBlank(theTokenParam.getSystem())) {
			theMatchUrl.myHashSystemAndValue = ResourceIndexedSearchParamToken.calculateHashSystemAndValue(
					myPartitionSettings,
					theRequestPartitionId,
					theMatchUrl.myResourceDefinition.getName(),
					theMatchUrl.myMatchUrlSearchMap.keySet().iterator().next(),
					theTokenParam.getSystem(),
					theTokenParam.getValue());
			theOutputSysAndValuePredicates.add(theMatchUrl.myHashSystemAndValue);
			return true;
		} else if (isNotBlank(theTokenParam.getValue())) {
			theMatchUrl.myHashValue = ResourceIndexedSearchParamToken.calculateHashValue(
					myPartitionSettings,
					theRequestPartitionId,
					theMatchUrl.myResourceDefinition.getName(),
					theMatchUrl.myMatchUrlSearchMap.keySet().iterator().next(),
					theTokenParam.getValue());
			theOutputValuePredicates.add(theMatchUrl.myHashValue);
			return true;
		}

		return false;
	}

	private ListMultimap<Long, MatchUrlToResolve> buildHashToSearchMap(
			List<MatchUrlToResolve> searchParameterMapsToResolve, String theIndex) {
		ListMultimap<Long, MatchUrlToResolve> hashToSearch = ArrayListMultimap.create();
		// Build a lookup map so we don't have to iterate over the searches repeatedly.
		for (MatchUrlToResolve nextSearchParameterMap : searchParameterMapsToResolve) {
			if (nextSearchParameterMap.myHashSystemAndValue != null && theIndex.equals("myHashSystemAndValue")) {
				hashToSearch.put(nextSearchParameterMap.myHashSystemAndValue, nextSearchParameterMap);
			}
			if (nextSearchParameterMap.myHashValue != null && theIndex.equals("myHashValue")) {
				hashToSearch.put(nextSearchParameterMap.myHashValue, nextSearchParameterMap);
			}
		}
		return hashToSearch;
	}

	@Override
	protected void flushSession(Map<IIdType, DaoMethodOutcome> theIdToPersistedOutcome) {
		try {
			int insertionCount;
			int updateCount;
			SessionImpl session = myEntityManager.unwrap(SessionImpl.class);
			if (session != null) {
				insertionCount = session.getActionQueue().numberOfInsertions();
				updateCount = session.getActionQueue().numberOfUpdates();
			} else {
				insertionCount = -1;
				updateCount = -1;
			}

			StopWatch sw = new StopWatch();
			myEntityManager.flush();
			ourLog.debug(
					"Session flush took {}ms for {} inserts and {} updates",
					sw.getMillis(),
					insertionCount,
					updateCount);
		} catch (PersistenceException e) {
			if (myHapiFhirHibernateJpaDialect != null) {
				String transactionTypes = createDescriptionOfResourceTypesInBundle(theIdToPersistedOutcome);
				String message = "Error flushing transaction with resource types: " + transactionTypes;
				throw myHapiFhirHibernateJpaDialect.translate(e, message);
			}
			throw e;
		}
	}

	@VisibleForTesting
	public void setIdHelperServiceForUnitTest(IIdHelperService<JpaPid> theIdHelperService) {
		myIdHelperService = theIdHelperService;
	}

	@VisibleForTesting
	public void setApplicationContextForUnitTest(ApplicationContext theAppCtx) {
		myApplicationContext = theAppCtx;
	}

	/**
	 * Creates a description of resource types in the provided bundle, indicating the types of resources
	 * and their counts within the input map. This is intended only to be helpful for troubleshooting, since
	 * it can be helpful to see details about the transaction which failed in the logs.
	 * <p>
	 * Example output: <code>[Patient (x3), Observation (x14)]</code>
	 * </p>
	 *
	 * @param theIdToPersistedOutcome A map where the key is an {@code IIdType} object representing a resource ID
	 *                                and the value is a {@code DaoMethodOutcome} object representing the outcome
	 *                                of the persistence operation for that resource.
	 * @return A string describing the resource types and their respective counts in a formatted list.
	 */
	@Nonnull
	private static String createDescriptionOfResourceTypesInBundle(
			Map<IIdType, DaoMethodOutcome> theIdToPersistedOutcome) {
		TreeMap<String, Integer> types = new TreeMap<>();
		for (IIdType t : theIdToPersistedOutcome.keySet()) {
			if (t != null) {
				String resourceType = t.getResourceType();
				int count = types.getOrDefault(resourceType, 0);
				types.put(resourceType, count + 1);
			}
		}

		StringBuilder typesBuilder = new StringBuilder();
		typesBuilder.append("[");
		for (Iterator<Map.Entry<String, Integer>> iter = types.entrySet().iterator(); iter.hasNext(); ) {
			Map.Entry<String, Integer> entry = iter.next();
			typesBuilder.append(entry.getKey());
			if (entry.getValue() > 1) {
				typesBuilder.append(" (x").append(entry.getValue()).append(")");
			}
			if (iter.hasNext()) {
				typesBuilder.append(", ");
			}
		}
		typesBuilder.append("]");
		return typesBuilder.toString();
	}

	public static class MatchUrlToResolve {

		private final String myRequestUrl;
		private final SearchParameterMap myMatchUrlSearchMap;
		private final RuntimeResourceDefinition myResourceDefinition;
		private final boolean myShouldPreFetchResourceBody;
		private final boolean myShouldPreFetchResourceVersion;
		public boolean myResolved;
		private Long myHashValue;
		private Long myHashSystemAndValue;

		public MatchUrlToResolve(
				String theRequestUrl,
				SearchParameterMap theMatchUrlSearchMap,
				RuntimeResourceDefinition theResourceDefinition,
				boolean theShouldPreFetchResourceBody,
				boolean theShouldPreFetchResourceVersion) {
			myRequestUrl = theRequestUrl;
			myMatchUrlSearchMap = theMatchUrlSearchMap;
			myResourceDefinition = theResourceDefinition;
			myShouldPreFetchResourceBody = theShouldPreFetchResourceBody;
			myShouldPreFetchResourceVersion = theShouldPreFetchResourceVersion;
		}

		public void setResolved(boolean theResolved) {
			myResolved = theResolved;
		}
	}
}
