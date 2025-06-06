/*
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
package ca.uhn.fhir.jpa.dao.data;

import ca.uhn.fhir.jpa.entity.TermCodeSystem;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.IdAndPartitionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ITermCodeSystemDao extends JpaRepository<TermCodeSystem, IdAndPartitionId>, IHapiFhirJpaRepository {

	@Query("SELECT cs FROM TermCodeSystem cs WHERE cs.myCodeSystemUri = :code_system_uri")
	TermCodeSystem findByCodeSystemUri(@Param("code_system_uri") String theCodeSystemUri);

	@Query("SELECT cs FROM TermCodeSystem cs WHERE cs.myResource.myPid = :resource_pid")
	TermCodeSystem findByResourcePid(@Param("resource_pid") JpaPid theResourcePid);

	@Query("SELECT cs FROM TermCodeSystem cs WHERE cs.myCurrentVersion.myId = :csv_pid")
	Optional<TermCodeSystem> findWithCodeSystemVersionAsCurrentVersion(@Param("csv_pid") Long theCodeSystemVersionPid);

	/**
	 * // TODO: JA2 Use partitioned query here
	 */
	@Deprecated
	@Query("SELECT cs FROM TermCodeSystem cs WHERE cs.myId = :pid")
	Optional<TermCodeSystem> findByPid(@Param("pid") long thePid);
}
