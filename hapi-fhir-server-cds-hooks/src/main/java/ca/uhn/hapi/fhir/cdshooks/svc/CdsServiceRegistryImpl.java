/*-
 * #%L
 * HAPI FHIR - CDS Hooks
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
package ca.uhn.hapi.fhir.cdshooks.svc;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsHooksExtension;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.hapi.fhir.cdshooks.api.CDSHooksVersion;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsMethod;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceMethod;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceFeedbackJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServicesJson;
import ca.uhn.hapi.fhir.cdshooks.serializer.CdsServiceRequestJsonDeserializer;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsPrefetchSvc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public class CdsServiceRegistryImpl implements ICdsServiceRegistry {
	private static final Logger ourLog = LoggerFactory.getLogger(CdsServiceRegistryImpl.class);
	private final CdsServiceRequestJsonDeserializer myCdsServiceRequestJsonDeserializer;
	private CdsServiceCache myServiceCache;

	private final CdsHooksContextBooter myCdsHooksContextBooter;
	private final CdsPrefetchSvc myCdsPrefetchSvc;
	private final ObjectMapper myObjectMapper;
	private final CDSHooksVersion myCdsHooksVersion;

	public CdsServiceRegistryImpl(
			CdsHooksContextBooter theCdsHooksContextBooter,
			CdsPrefetchSvc theCdsPrefetchSvc,
			ObjectMapper theObjectMapper,
			CdsServiceRequestJsonDeserializer theCdsServiceRequestJsonDeserializer) {
		this(
				theCdsHooksContextBooter,
				theCdsPrefetchSvc,
				theObjectMapper,
				theCdsServiceRequestJsonDeserializer,
				CDSHooksVersion.DEFAULT);
	}

	public CdsServiceRegistryImpl(
			CdsHooksContextBooter theCdsHooksContextBooter,
			CdsPrefetchSvc theCdsPrefetchSvc,
			ObjectMapper theObjectMapper,
			CdsServiceRequestJsonDeserializer theCdsServiceRequestJsonDeserializer,
			CDSHooksVersion theCDSHooksVersion) {
		myCdsHooksContextBooter = theCdsHooksContextBooter;
		myCdsPrefetchSvc = theCdsPrefetchSvc;
		myObjectMapper = theObjectMapper;
		myCdsServiceRequestJsonDeserializer = theCdsServiceRequestJsonDeserializer;
		myCdsHooksVersion = theCDSHooksVersion;
	}

	@PostConstruct
	public void init() {
		myServiceCache = myCdsHooksContextBooter.buildCdsServiceCache();
	}

	@Override
	public CdsServicesJson getCdsServicesJson() {
		return myServiceCache.getCdsServicesJson();
	}

	@Override
	public CdsServiceResponseJson callService(String theServiceId, Object theCdsServiceRequestJson) {
		final CdsServiceJson cdsServiceJson = getCdsServiceJson(theServiceId);
		final CdsServiceRequestJson deserializedRequest =
				myCdsServiceRequestJsonDeserializer.deserialize(cdsServiceJson, theCdsServiceRequestJson);
		validateHookRequestFhirServer(deserializedRequest);
		ICdsServiceMethod serviceMethod = (ICdsServiceMethod) getCdsServiceMethodOrThrowException(theServiceId);
		myCdsPrefetchSvc.augmentRequest(deserializedRequest, serviceMethod);
		Object response = serviceMethod.invoke(myObjectMapper, deserializedRequest, theServiceId);
		return encodeServiceResponse(theServiceId, response);
	}

	@Override
	public CdsServiceFeedbackJson callFeedback(String theServiceId, CdsServiceFeedbackJson theCdsServiceFeedbackJson) {
		ICdsMethod feedbackMethod = getCdsFeedbackMethodOrThrowException(theServiceId);
		Object response = feedbackMethod.invoke(myObjectMapper, theCdsServiceFeedbackJson, theServiceId);
		return encodeFeedbackResponse(theServiceId, response);
	}

	/**
	 * @see ICdsServiceRegistry#registerService
	 */
	@Override
	public void registerService(
			String theServiceId,
			Function<CdsServiceRequestJson, CdsServiceResponseJson> theServiceFunction,
			CdsServiceJson theCdsServiceJson,
			boolean theAllowAutoFhirClientPrefetch,
			String theServiceGroupId) {
		if (theCdsServiceJson.getExtensionClass() == null) {
			theCdsServiceJson.setExtensionClass(CdsHooksExtension.class);
		}
		myServiceCache.registerDynamicService(
				theServiceId, theServiceFunction, theCdsServiceJson, theAllowAutoFhirClientPrefetch, theServiceGroupId);
	}

	/**
	 * @see ICdsServiceRegistry#unregisterService
	 */
	@Override
	public void unregisterService(@Nonnull String theServiceId, String theServiceGroupId) {
		Validate.notNull(theServiceId, "CDS Hook Service Id cannot be null");
		ICdsMethod activeService = myServiceCache.unregisterServiceMethod(theServiceId, theServiceGroupId);
		if (activeService != null) {
			ourLog.info("Unregistered active service {}", theServiceId);
		}
	}

	/**
	 * @see ICdsServiceRegistry#unregisterServices
	 */
	@Override
	public void unregisterServices(@Nonnull String theServiceGroupId) {
		Validate.notNull(theServiceGroupId, "CDS Hook Service Group Id cannot be null");
		myServiceCache.unregisterServices(theServiceGroupId);
	}

	@Override
	public CdsServiceJson getCdsServiceJson(String theServiceId) {
		CdsServiceJson cdsServiceJson = myServiceCache.getCdsServiceJson(theServiceId);
		if (cdsServiceJson == null) {
			throw new IllegalArgumentException(Msg.code(2536) + "No service with " + theServiceId + " is registered.");
		}
		return cdsServiceJson;
	}

	@Nonnull
	private ICdsMethod getCdsServiceMethodOrThrowException(String theId) {
		ICdsMethod retval = myServiceCache.getServiceMethod(theId);
		if (retval == null) {
			throw new ResourceNotFoundException(
					Msg.code(2391) + "No service with id " + theId + " is registered on this server");
		}
		return retval;
	}

	@Nonnull
	CdsServiceResponseJson encodeServiceResponse(String theServiceId, Object result) {
		if (result instanceof String) {
			return buildResponseFromString(theServiceId, result, (String) result);
		} else {
			return buildResponseFromImplementation(theServiceId, result);
		}
	}

	@Nonnull
	private ICdsMethod getCdsFeedbackMethodOrThrowException(String theId) {
		ICdsMethod retval = myServiceCache.getFeedbackMethod(theId);
		if (retval == null) {
			throw new ResourceNotFoundException(
					Msg.code(2392) + "No feedback service with id " + theId + " is registered on this server");
		}
		return retval;
	}

	@Nonnull
	CdsServiceFeedbackJson encodeFeedbackResponse(String theServiceId, Object theResponse) {
		if (theResponse instanceof String) {
			return buildFeedbackFromString(theServiceId, (String) theResponse);
		} else {
			return buildFeedbackFromImplementation(theServiceId, theResponse);
		}
	}

	private CdsServiceResponseJson buildResponseFromImplementation(String theServiceId, Object theResult) {
		try {
			return (CdsServiceResponseJson) theResult;
		} catch (ClassCastException e) {
			throw new ConfigurationException(
					Msg.code(2389)
							+ "Failed to cast Cds service response to CdsServiceResponseJson when calling CDS Hook Service "
							+ theServiceId + ". The type "
							+ theResult.getClass().getName()
							+ " cannot be casted to CdsServiceResponseJson",
					e);
		}
	}

	private CdsServiceResponseJson buildResponseFromString(String theServiceId, Object theResult, String theJson) {
		try {
			return myObjectMapper.readValue(theJson, CdsServiceResponseJson.class);
		} catch (JsonProcessingException e) {
			throw new ConfigurationException(
					Msg.code(2390) + "Failed to json deserialize Cds service response of type "
							+ theResult.getClass().getName() + " when calling CDS Hook Service " + theServiceId
							+ ".  Json: " + theJson,
					e);
		}
	}

	private CdsServiceFeedbackJson buildFeedbackFromImplementation(String theServiceId, Object theResponse) {
		try {
			return (CdsServiceFeedbackJson) theResponse;
		} catch (ClassCastException e) {
			throw new ClassCastException(
					Msg.code(2537) + "Failed to cast feedback response CdsServiceFeedbackJson for service "
							+ theServiceId + ". " + e.getMessage());
		}
	}

	private CdsServiceFeedbackJson buildFeedbackFromString(String theServiceId, String theResponse) {
		try {
			return myObjectMapper.readValue(theResponse, CdsServiceFeedbackJson.class);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(Msg.code(2538) + "Failed to serialize json Cds Feedback response for service "
					+ theServiceId + ". " + e.getMessage());
		}
	}

	@VisibleForTesting
	void setServiceCache(CdsServiceCache theCdsServiceCache) {
		myServiceCache = theCdsServiceCache;
	}

	private void validateHookRequestFhirServer(CdsServiceRequestJson theCdsServiceRequestJson) {
		if (myCdsHooksVersion != CDSHooksVersion.V_1_1) {
			// for a version greater than V_1_1 (which is the base version supported),
			// the fhirServer is required to use https scheme
			String fhirServer = theCdsServiceRequestJson.getFhirServer();
			if (fhirServer != null && !fhirServer.startsWith("https")) {
				throw new InvalidRequestException(Msg.code(2632) + "The scheme for the fhirServer must be https");
			}
		}
	}
}
