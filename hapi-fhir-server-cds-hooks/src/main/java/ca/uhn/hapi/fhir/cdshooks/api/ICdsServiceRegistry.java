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
package ca.uhn.hapi.fhir.cdshooks.api;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceFeedbackJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServicesJson;

import java.util.function.Function;

/**
 * This registry holds all CDS Hooks services registered with the server.
 */
public interface ICdsServiceRegistry {
	/**
	 * This is the json returned by calling https://example.com/cds-services
	 *
	 * @return a list of CDS Hooks service descriptors
	 */
	CdsServicesJson getCdsServicesJson();

	/**
	 * This is the REST method available at https://example.com/cds-services/{theServiceId}
	 *
	 * @param theServiceId the id of the service to be called
	 * @param theCdsServiceRequestJson the service request
	 * @return the service response
	 */
	CdsServiceResponseJson callService(String theServiceId, Object theCdsServiceRequestJson);

	/**
	 * This is the REST method available at https://example.com/cds-services/{theServiceId}/feedback
	 *
	 * @param theServiceId the id of the service that feedback is being sent for
	 * @param theCdsServiceFeedbackJson the request
	 * @return the response
	 */
	CdsServiceFeedbackJson callFeedback(String theServiceId, CdsServiceFeedbackJson theCdsServiceFeedbackJson);

	/**
	 * Register a new CDS Service with the endpoint.
	 *
	 * @param theServiceId                   the id of the service
	 * @param theServiceFunction             the function that will be called to invoke the service
	 * @param theCdsServiceJson              the service descriptor
	 * @param theAllowAutoFhirClientPrefetch Whether to allow the server to automatically prefetch resources
	 * @param theServiceGroupId              the serviceGroupId where the service is registered
	 */
	void registerService(
			String theServiceId,
			Function<CdsServiceRequestJson, CdsServiceResponseJson> theServiceFunction,
			CdsServiceJson theCdsServiceJson,
			boolean theAllowAutoFhirClientPrefetch,
			String theServiceGroupId);

	/**
	 * Remove registered CDS service with the service ID, only removes dynamically registered service
	 *
	 * @param theServiceId the id of the service to be removed
	 * @param theServiceGroupId the id of the group where the service is registered
	 */
	void unregisterService(String theServiceId, String theServiceGroupId);

	/**
	 * Remove registered CDS services with the Group ID, only removes dynamically registered services
	 *
	 * @param theServiceGroupId the id of the group where the services are registered
	 */
	void unregisterServices(String theServiceGroupId);

	/**
	 * Get registered CDS service with service ID
	 * @param theServiceId the id of the service to be retrieved
	 * @return CdsServiceJson
	 * @throws IllegalArgumentException if a CDS service with provided serviceId is not found
	 */
	CdsServiceJson getCdsServiceJson(String theServiceId);
}
