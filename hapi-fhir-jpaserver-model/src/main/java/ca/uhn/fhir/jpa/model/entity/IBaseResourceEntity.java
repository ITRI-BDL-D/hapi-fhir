/*
 * #%L
 * HAPI FHIR JPA Model
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
package ca.uhn.fhir.jpa.model.entity;

import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.model.primitive.InstantDt;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Date;

/**
 * @param <T> The resource PID type
 */
public interface IBaseResourceEntity<T> {

	Date getDeleted();

	FhirVersionEnum getFhirVersion();

	@Nonnull
	T getId();

	IdDt getIdDt();

	InstantDt getPublished();

	JpaPid getResourceId();

	String getResourceType();

	Short getResourceTypeId();

	InstantDt getUpdated();

	Date getUpdatedDate();

	long getVersion();

	boolean isHasTags();

	@Nullable
	PartitionablePartitionId getPartitionId();
}
