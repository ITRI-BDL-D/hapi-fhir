package ca.uhn.fhir.jpa.dao.r4;

import static ca.uhn.fhir.interceptor.model.RequestPartitionId.defaultPartition;
import static ca.uhn.fhir.interceptor.model.RequestPartitionId.fromPartitionId;
import static ca.uhn.fhir.interceptor.model.RequestPartitionId.fromPartitionIds;
import static ca.uhn.fhir.interceptor.model.RequestPartitionId.fromPartitionNames;
import static ca.uhn.fhir.jpa.model.entity.ResourceTable.IDX_RES_TYPE_FHIR_ID;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.ReadPartitionIdRequestDetails;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.entity.PartitionEntity;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.searchparam.submit.interceptor.SearchParamValidatingInterceptor;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.util.HapiExtensions;
import com.helger.commons.lang.StackTraceHelper;
import jakarta.annotation.Nonnull;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.SearchParameter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public abstract class BasePartitioningR4Test extends BaseJpaR4SystemTest {
	static final String PARTITION_1 = "PART-1";
	static final String PARTITION_2 = "PART-2";
	static final String PARTITION_3 = "PART-3";
	static final String PARTITION_4 = "PART-4";
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PartitioningSqlR4Test.class);
	protected MyReadWriteInterceptor myPartitionInterceptor;
	protected LocalDate myPartitionDate;
	protected LocalDate myPartitionDate2;
	protected int myPartitionId;
	protected int myPartitionId2;
	protected int myPartitionId3;
	protected int myPartitionId4;
	private boolean myHaveDroppedForcedIdUniqueConstraint;
	@Autowired
	protected IPartitionLookupSvc myPartitionConfigSvc;
	@Autowired
	private SearchParamValidatingInterceptor mySearchParamValidatingInterceptor;
	private boolean myRegisteredSearchParamValidatingInterceptor;

	@AfterEach
	public void after() {
		assertNoRemainingPartitionIds();

		PartitionSettings defaultPartitionSettings = new PartitionSettings();
		JpaStorageSettings defaultStorageSettings = new JpaStorageSettings();

		myPartitionSettings.setIncludePartitionInSearchHashes(defaultPartitionSettings.isIncludePartitionInSearchHashes());
		myPartitionSettings.setPartitioningEnabled(defaultPartitionSettings.isPartitioningEnabled());
		myPartitionSettings.setAllowReferencesAcrossPartitions(defaultPartitionSettings.getAllowReferencesAcrossPartitions());
		myPartitionSettings.setDefaultPartitionId(defaultPartitionSettings.getDefaultPartitionId());

		mySrdInterceptorService.unregisterInterceptorsIf(t -> t instanceof MyReadWriteInterceptor);

		myStorageSettings.setIndexMissingFields(defaultStorageSettings.getIndexMissingFields());
		myStorageSettings.setAutoCreatePlaceholderReferenceTargets(defaultStorageSettings.isAutoCreatePlaceholderReferenceTargets());
		myStorageSettings.setMassIngestionMode(defaultStorageSettings.isMassIngestionMode());
		myStorageSettings.setMatchUrlCacheEnabled(defaultStorageSettings.isMatchUrlCacheEnabled());

		if (myRegisteredSearchParamValidatingInterceptor) {
			myInterceptorRegistry.unregisterInterceptor(mySearchParamValidatingInterceptor);
		}
	}

	protected void assertNoRemainingPartitionIds() {
		myPartitionInterceptor.assertNoRemainingIds();
	}

	@Override
	@BeforeEach
	public void before() throws Exception {
		super.before();
		myPartitionSettings.setPartitioningEnabled(true);
		myPartitionSettings.setIncludePartitionInSearchHashes(new PartitionSettings().isIncludePartitionInSearchHashes());

		myStorageSettings.setUniqueIndexesEnabled(true);

		myStorageSettings.setDefaultSearchParamsCanBeOverridden(true);

		myPartitionDate = LocalDate.of(2020, Month.JANUARY, 14);
		myPartitionDate2 = LocalDate.of(2020, Month.FEBRUARY, 15);
		myPartitionId = 1;
		myPartitionId2 = 2;
		myPartitionId3 = 3;
		myPartitionId4 = 4;

		myPartitionInterceptor = new MyReadWriteInterceptor();

		registerPartitionInterceptor();

		myPartitionConfigSvc.createPartition(new PartitionEntity().setId(myPartitionId).setName(PARTITION_1), null);
		myPartitionConfigSvc.createPartition(new PartitionEntity().setId(myPartitionId2).setName(PARTITION_2), null);
		myPartitionConfigSvc.createPartition(new PartitionEntity().setId(myPartitionId3).setName(PARTITION_3), null);
		myPartitionConfigSvc.createPartition(new PartitionEntity().setId(myPartitionId4).setName(PARTITION_4), null);

		myStorageSettings.setIndexMissingFields(JpaStorageSettings.IndexEnabledEnum.ENABLED);

		// Ensure the partition names are resolved
		addNextInterceptorReadResult(fromPartitionNames(JpaConstants.DEFAULT_PARTITION_NAME, PARTITION_1, PARTITION_2, PARTITION_3, PARTITION_4));
		myPatientDao.search(new SearchParameterMap().setLoadSynchronous(true), mySrd);

		// Pre-fetch the partitions by ID
		for (int i = 1; i <= 4; i++) {
			myPartitionConfigSvc.getPartitionById(i);
		}

		if (myInterceptorRegistry.getAllRegisteredInterceptors().stream().noneMatch(t->t instanceof SearchParamValidatingInterceptor)) {
			myRegisteredSearchParamValidatingInterceptor = true;
			myInterceptorRegistry.registerInterceptor(mySearchParamValidatingInterceptor);
		}
	}

	protected void registerPartitionInterceptor() {
		mySrdInterceptorService.registerInterceptor(myPartitionInterceptor);
	}

	@Override
	public void afterPurgeDatabase() {
		super.afterPurgeDatabase();

		if (myHaveDroppedForcedIdUniqueConstraint) {
			runInTransaction(() -> {
				myEntityManager.createNativeQuery("delete from HFJ_RESOURCE").executeUpdate();
				myEntityManager.createNativeQuery("alter table " + ResourceTable.HFJ_RESOURCE +
					" add constraint " + IDX_RES_TYPE_FHIR_ID + " unique (RES_TYPE, FHIR_ID)").executeUpdate();
			});
		}
	}

	protected void createUniqueComboSp() {
		addNextTargetPartitionForCreateWithIdDefaultPartition();
		addNextTargetPartitionForReadDefaultPartition(); // one for search param validation
		SearchParameter sp = new SearchParameter();
		sp.setId("SearchParameter/patient-gender");
		sp.setType(Enumerations.SearchParamType.TOKEN);
		sp.setCode("gender");
		sp.setExpression("Patient.gender");
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.addBase("Patient");
		mySearchParameterDao.update(sp, mySrd);

		addNextTargetPartitionForCreateWithIdDefaultPartition();
		addNextTargetPartitionForReadDefaultPartition(); // one for search param validation
		sp = new SearchParameter();
		sp.setId("SearchParameter/patient-family");
		sp.setType(Enumerations.SearchParamType.STRING);
		sp.setCode("family");
		sp.setExpression("Patient.name[0].family");
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.addBase("Patient");
		mySearchParameterDao.update(sp, mySrd);

		addNextTargetPartitionForCreateWithIdDefaultPartition();
		sp = new SearchParameter();
		sp.setId("SearchParameter/patient-gender-family-unique");
		sp.setType(Enumerations.SearchParamType.COMPOSITE);
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.addBase("Patient");
		sp.addComponent()
			.setExpression("Patient")
			.setDefinition("SearchParameter/patient-gender");
		sp.addComponent()
			.setExpression("Patient")
			.setDefinition("SearchParameter/patient-family");
		sp.addExtension()
			.setUrl(HapiExtensions.EXT_SP_UNIQUE)
			.setValue(new BooleanType(true));
		mySearchParameterDao.update(sp, mySrd);

		mySearchParamRegistry.forceRefresh();

		assertNoRemainingPartitionIds();
	}

	protected void createNonUniqueComboSp() {
		addNextTargetPartitionForCreateWithIdDefaultPartition();
		addNextTargetPartitionForReadDefaultPartition(); // one for search param validation
		SearchParameter sp = new SearchParameter();
		sp.setId("SearchParameter/patient-family");
		sp.setType(Enumerations.SearchParamType.STRING);
		sp.setCode("family");
		sp.setExpression("Patient.name.family");
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.addBase("Patient");
		mySearchParameterDao.update(sp, mySrd);

		addNextTargetPartitionForCreateWithIdDefaultPartition();
		addNextTargetPartitionForReadDefaultPartition(); // one for search param validation
		sp = new SearchParameter();
		sp.setId("SearchParameter/patient-managingorg");
		sp.setType(Enumerations.SearchParamType.REFERENCE);
		sp.setCode(Patient.SP_ORGANIZATION);
		sp.setExpression("Patient.managingOrganization");
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.addBase("Patient");
		mySearchParameterDao.update(sp, mySrd);

		addNextTargetPartitionForCreateWithIdDefaultPartition();
		sp = new SearchParameter();
		sp.setId("SearchParameter/patient-family-and-org");
		sp.setType(Enumerations.SearchParamType.COMPOSITE);
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.addBase("Patient");
		sp.addComponent()
			.setExpression("Patient")
			.setDefinition("SearchParameter/patient-family");
		sp.addComponent()
			.setExpression("Patient")
			.setDefinition("SearchParameter/patient-managingorg");
		sp.addExtension()
			.setUrl(HapiExtensions.EXT_SP_UNIQUE)
			.setValue(new BooleanType(false));
		mySearchParameterDao.update(sp, mySrd);

		mySearchParamRegistry.forceRefresh();
	}

	protected void dropForcedIdUniqueConstraint() {
		runInTransaction(() -> {
			myEntityManager.createNativeQuery("alter table " + ResourceTable.HFJ_RESOURCE + " drop constraint " + IDX_RES_TYPE_FHIR_ID).executeUpdate();
		});
		myHaveDroppedForcedIdUniqueConstraint = true;
	}

	protected void addNextTargetPartitionForCreate(Integer thePartitionId) {
		addNextTargetPartitionForCreate(thePartitionId, null);
	}

	// all the create-paths have a read path first for the tx boundary.
	protected void addNextTargetPartitionForCreate(RequestPartitionId requestPartitionId) {
		addNextInterceptorReadResult(requestPartitionId);
		addNextInterceptorCreateResult(requestPartitionId);
	}

	protected void addNextTargetPartitionForConditionalCreateMatch(RequestPartitionId requestPartitionId) {
		// only read to find the existing resource
		addNextInterceptorReadResult(requestPartitionId);
	}

	// all the create-paths have a read path first for the tx boundary.
	protected void addNextTargetPartitionForConditionalUpdateNotExist(RequestPartitionId requestPartitionId) {
		addNextInterceptorReadResult(requestPartitionId);
		addNextInterceptorCreateResult(requestPartitionId);
		addNextInterceptorCreateResult(requestPartitionId);
	}

	protected void addNextTargetPartitionForConditionalUpdateExist(RequestPartitionId requestPartitionId) {
		addNextInterceptorReadResult(requestPartitionId);
		addNextInterceptorCreateResult(requestPartitionId);
	}

	protected void addNextInterceptorCreateResult(RequestPartitionId requestPartitionId) {
		myPartitionInterceptor.addNextInterceptorCreateResult(requestPartitionId);
	}

	/**
	 * the create with client-assigned id path calls create 2 times: once for entry tx boundary, and then again to assign the partition
	 */
	protected void addNextTargetPartitionForCreateWithId(RequestPartitionId requestPartitionId) {
		addNextInterceptorCreateResult(requestPartitionId);
		addNextInterceptorCreateResult(requestPartitionId);
	}

	/** Actual update of an existing resource.
	 * We only need one call for the tx boundary, since the actual partition is already assigned. */
	protected void addNextTargetPartitionForUpdate(RequestPartitionId theRequestPartitionId) {
		addNextInterceptorCreateResult(theRequestPartitionId);
	}

	/**
	 * We need lots of calls: pre-fetch, main tx boundary, redundant boundary for dao.update(), and finally the assign call
	 */
	private void addNextTargetPartitionForUpdateInTxBundle(RequestPartitionId requestPartitionId) {
		addNextInterceptorCreateResult(requestPartitionId);
		addNextInterceptorCreateResult(requestPartitionId);
		addNextTargetPartitionForCreateWithId(requestPartitionId);
	}

	protected void addNextTargetPartitionForUpdateInTxBundle(int thePartitionId) {
		addNextTargetPartitionForUpdateInTxBundle(fromPartitionId(thePartitionId));
	}

	protected void addNextTargetPartitionForCreate(Integer thePartitionId, LocalDate thePartitionDate) {
		Validate.notNull(thePartitionId);
		RequestPartitionId requestPartitionId = fromPartitionId(thePartitionId, thePartitionDate);
		addNextTargetPartitionForCreate(requestPartitionId);
	}

	protected void addNextTargetPartitionForCreateDefaultPartition() {
		addNextTargetPartitionForCreate(defaultPartition());
	}

	protected void addNextTargetPartitionForCreateDefaultPartition(LocalDate thePartitionDate) {
		RequestPartitionId requestPartitionId = fromPartitionId(null, thePartitionDate);
		addNextTargetPartitionForCreate(requestPartitionId);
	}


	protected void addNextTargetPartitionForCreateWithId(int thePartitionId, LocalDate thePartitionDate) {
		addNextTargetPartitionForCreateWithId(fromPartitionId(thePartitionId, thePartitionDate));
	}

	protected void addNextTargetPartitionForCreateWithId(int thePartitionId) {
		addNextTargetPartitionForCreateWithId(fromPartitionId(thePartitionId));
	}

	protected void addNextTargetPartitionForCreateWithIdDefaultPartition() {
		addNextTargetPartitionForCreateWithId(defaultPartition());
	}

	protected void addNextTargetPartitionForCreateWithIdDefaultPartition(LocalDate thePartitionDate) {
		RequestPartitionId requestPartitionId = fromPartitionId(null, thePartitionDate);
		addNextTargetPartitionForCreateWithId(requestPartitionId);
	}

	protected void addNextTargetPartitionForCreateWithIdDefaultPartition(Integer thePartitionId, LocalDate thePartitionDate) {
		RequestPartitionId requestPartitionId = fromPartitionId(thePartitionId, thePartitionDate);
		addNextTargetPartitionForCreateWithId(requestPartitionId);
	}

	protected void addNextTargetPartitionForUpdate(int thePartitionId) {
		addNextTargetPartitionForUpdate(fromPartitionId(thePartitionId));
	}


	protected void addNextTargetPartitionsForRead(Integer... thePartitionId) {
		Validate.notNull(thePartitionId);
		addNextInterceptorReadResult(fromPartitionIds(thePartitionId));
	}

	protected void addNextTargetPartitionsForRead(String... thePartitionNames) {
		Validate.notNull(thePartitionNames);
		Validate.isTrue(thePartitionNames.length > 0);
		addNextInterceptorReadResult(fromPartitionNames(thePartitionNames));
	}

	protected void addNextTargetPartitionForReadDefaultPartition() {
		addNextInterceptorReadResult(defaultPartition());
	}

	protected void addNextInterceptorReadResult(RequestPartitionId requestPartitionId) {
		myPartitionInterceptor.addNextIterceptorReadResult(requestPartitionId);
	}

	protected void addNextTargetPartitionForReadAllPartitions() {
		addNextInterceptorReadResult(RequestPartitionId.allPartitions());
	}

	public void createRequestId() {
		when(mySrd.getRequestId()).thenReturn("REQUEST_ID");
	}

	protected ICreationArgument withUpdatePartition(Integer thePartitionId) {
		return t -> {
			if (thePartitionId != null) {
				addNextTargetPartitionForCreateWithId(thePartitionId);
			} else {
				addNextTargetPartitionForCreateWithIdDefaultPartition();
			}
		};

	}

	protected ICreationArgument withCreatePartition(Integer thePartitionId) {
		return t -> {
			if (thePartitionId != null) {
				addNextTargetPartitionForCreate(thePartitionId, null);
			} else {
				addNextTargetPartitionForCreateDefaultPartition();
			}
		};
	}

	protected ICreationArgument withReadWritePartitions(Integer thePartitionId) {
		return t -> {
			if (thePartitionId != null) {
				addNextTargetPartitionsForRead(thePartitionId);
				addNextTargetPartitionForCreate(thePartitionId, null);
			} else {
				addNextTargetPartitionForReadDefaultPartition();
				addNextTargetPartitionForCreateDefaultPartition();
			}
		};
	}

	@Interceptor
	public static class MyReadWriteInterceptor extends MyWriteInterceptor {


		private final List<RequestPartitionId> myReadRequestPartitionIds = new ArrayList<>();

		public void addNextIterceptorReadResult(RequestPartitionId theRequestPartitionId) {
			myReadRequestPartitionIds.add(theRequestPartitionId);
			ourLog.info("Adding partition {} for read (not have {})", theRequestPartitionId, myReadRequestPartitionIds.size());
		}

		@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_READ)
		public RequestPartitionId partitionIdentifyRead(ServletRequestDetails theRequestDetails,
																		ReadPartitionIdRequestDetails theDetails) {

			// Just to be nice, figure out the first line in the stack that isn't a part of the
			// partitioning or interceptor infrastructure, just so it's obvious who is asking
			// for a partition ID
			String stack = getCallerStackLine();

			assertThat(myReadRequestPartitionIds).describedAs("read partition ids").isNotEmpty();
			RequestPartitionId retVal = myReadRequestPartitionIds.remove(0);
			ourLog.info("Returning partition {} for read at: {}", retVal, stack);
			return retVal;
		}

		@Override
		public void assertNoRemainingIds() {
			super.assertNoRemainingIds();
			assertThat(myReadRequestPartitionIds).as("Found " + myReadRequestPartitionIds.size() + " READ partitions remaining in interceptor").isEmpty();
		}

	}

	@Nonnull
	private static String getCallerStackLine() {
		String stack;
		try {
			throw new Exception();
		} catch (Exception e) {
			stack = StackTraceHelper.getStackAsString(e);
			stack = Arrays.stream(stack.split("\\n"))
				.filter(t->t.contains("ca.uhn.fhir"))
				.filter(t->!t.toLowerCase().contains("interceptor"))
				.filter(t->!t.toLowerCase().contains("partitionhelper"))
				.filter(t->!t.contains("Test"))
				.findFirst()
				.orElse("UNKNOWN");
		}
		return stack;
	}

	@Interceptor
	public static class MyWriteInterceptor {

		private final List<RequestPartitionId> myCreateRequestPartitionIds = new ArrayList<>();

		public void addNextInterceptorCreateResult(RequestPartitionId theRequestPartitionId) {
			myCreateRequestPartitionIds.add(theRequestPartitionId);
		}

		@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_CREATE)
		public RequestPartitionId PartitionIdentifyCreate(IBaseResource theResource, ServletRequestDetails theRequestDetails) {
			assertNotNull(theResource);
			String stack = getCallerStackLine();
			assertThat(myCreateRequestPartitionIds).describedAs("create partitions").isNotEmpty();
			RequestPartitionId retVal = myCreateRequestPartitionIds.remove(0);
			ourLog.info("Returning partition [{}] for create of resource {} with date {}: {}", retVal, theResource, retVal.getPartitionDate(), stack);
			return retVal;
		}

		public void assertNoRemainingIds() {
			assertThat(myCreateRequestPartitionIds).as(() -> "Still have " + myCreateRequestPartitionIds.size() + " CREATE partitions remaining in interceptor").isEmpty();
		}

	}
}
