package ca.uhn.fhirtest.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.config.HapiJpaConfig;
import ca.uhn.fhir.jpa.config.dstu3.JpaDstu3Config;
import ca.uhn.fhir.jpa.config.util.HapiEntityManagerFactoryUtil;
import ca.uhn.fhir.jpa.model.config.SubscriptionSettings;
import ca.uhn.fhir.jpa.model.dialect.HapiFhirH2Dialect;
import ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgres94Dialect;
import ca.uhn.fhir.jpa.search.DatabaseBackedPagingProvider;
import ca.uhn.fhir.jpa.util.CurrentThreadCaptureQueriesListener;
import ca.uhn.fhir.jpa.validation.ValidationSettings;
import ca.uhn.fhir.rest.server.interceptor.RequestValidatingInterceptor;
import ca.uhn.fhir.validation.IInstanceValidatorModule;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhirtest.interceptor.PublicSecurityInterceptor;
import jakarta.persistence.EntityManagerFactory;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.apache.commons.dbcp2.BasicDataSource;
import org.hl7.fhir.dstu2.model.Subscription;
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

@Configuration
@Import({CommonConfig.class, JpaDstu3Config.class, HapiJpaConfig.class})
@EnableTransactionManagement()
public class TestDstu3Config extends BaseConfig {
	public static final String FHIR_LUCENE_LOCATION_DSTU3 = "fhir.lucene.location.dstu3";

	private String myDbUsername = System.getProperty(TestR5Config.FHIR_DB_USERNAME);
	private String myDbPassword = System.getProperty(TestR5Config.FHIR_DB_PASSWORD);
	private String myFhirLuceneLocation = System.getProperty(FHIR_LUCENE_LOCATION_DSTU3);

	@Bean
	public SubscriptionSettings subscriptionSettings() {
		SubscriptionSettings retVal = new SubscriptionSettings();
		retVal.addSupportedSubscriptionType(Subscription.SubscriptionChannelType.RESTHOOK);
		retVal.setWebsocketContextPath("/websocketDstu3");
		return retVal;
	}

	@Bean
	public JpaStorageSettings storageSettings() {
		JpaStorageSettings retVal = new JpaStorageSettings();
		retVal.setAllowContainsSearches(true);
		retVal.setAllowMultipleDelete(true);
		retVal.setAllowInlineMatchUrlReferences(true);
		retVal.setAllowExternalReferences(true);
		retVal.getTreatBaseUrlsAsLocal().add("http://hapi.fhir.org/baseDstu3");
		retVal.getTreatBaseUrlsAsLocal().add("https://hapi.fhir.org/baseDstu3");
		retVal.getTreatBaseUrlsAsLocal().add("http://fhirtest.uhn.ca/baseDstu3");
		retVal.getTreatBaseUrlsAsLocal().add("https://fhirtest.uhn.ca/baseDstu3");
		retVal.setCountSearchResultsUpTo(TestR4Config.COUNT_SEARCH_RESULTS_UP_TO);
		retVal.setIndexMissingFields(JpaStorageSettings.IndexEnabledEnum.ENABLED);
		retVal.setFetchSizeDefaultMaximum(10000);
		retVal.setReindexThreadCount(1);
		retVal.setExpungeEnabled(true);
		retVal.setFilterParameterEnabled(true);
		retVal.setDefaultSearchParamsCanBeOverridden(false);
		retVal.setIndexOnContainedResources(true);
		retVal.setIndexIdentifierOfType(true);
		return retVal;
	}

	@Bean
	public ValidationSettings validationSettings() {
		ValidationSettings retVal = new ValidationSettings();
		retVal.setLocalReferenceValidationDefaultPolicy(ReferenceValidationPolicy.CHECK_VALID);
		return retVal;
	}

	@Bean
	public DatabaseBackedPagingProvider databaseBackedPagingProvider() {
		DatabaseBackedPagingProvider retVal = new DatabaseBackedPagingProvider();
		retVal.setDefaultPageSize(20);
		retVal.setMaximumPageSize(500);
		return retVal;
	}

	@Bean
	public PublicSecurityInterceptor securityInterceptor() {
		return new PublicSecurityInterceptor();
	}

	@Bean(name = "myPersistenceDataSourceDstu3")
	public DataSource dataSource() {
		BasicDataSource retVal = new BasicDataSource();
		if (CommonConfig.isLocalTestMode()) {
			retVal.setUrl("jdbc:h2:mem:fhirtest_dstu3");
		} else {
			retVal.setDriver(new org.postgresql.Driver());
			retVal.setUrl("jdbc:postgresql://localhost/fhirtest_dstu3");
		}
		retVal.setUsername(myDbUsername);
		retVal.setPassword(myDbPassword);
		TestR5Config.applyCommonDatasourceParams(retVal);

		DataSource dataSource = ProxyDataSourceBuilder.create(retVal)
				//			.logQueryBySlf4j(SLF4JLogLevel.INFO, "SQL")
				.logSlowQueryBySlf4j(10000, TimeUnit.MILLISECONDS)
				.afterQuery(new CurrentThreadCaptureQueriesListener())
				.countQuery()
				.build();

		return dataSource;
	}

	@Bean
	public LocalContainerEntityManagerFactoryBean entityManagerFactory(
			ConfigurableListableBeanFactory theConfigurableListableBeanFactory,
			FhirContext theFhirContext,
			JpaStorageSettings theStorageSettings) {
		LocalContainerEntityManagerFactoryBean retVal = HapiEntityManagerFactoryUtil.newEntityManagerFactory(
				theConfigurableListableBeanFactory, theFhirContext, theStorageSettings);
		retVal.setPersistenceUnitName("PU_HapiFhirJpaDstu3");
		retVal.setDataSource(dataSource());
		retVal.setJpaProperties(jpaProperties());
		return retVal;
	}

	private Properties jpaProperties() {
		Properties extraProperties = new Properties();
		if (CommonConfig.isLocalTestMode()) {
			extraProperties.put("hibernate.dialect", HapiFhirH2Dialect.class.getName());
		} else {
			extraProperties.put("hibernate.dialect", HapiFhirPostgres94Dialect.class.getName());
		}
		extraProperties.put("hibernate.format_sql", "false");
		extraProperties.put("hibernate.show_sql", "false");
		extraProperties.put("hibernate.hbm2ddl.auto", "none");
		extraProperties.put("hibernate.jdbc.batch_size", "20");
		extraProperties.put("hibernate.cache.use_query_cache", "false");
		extraProperties.put("hibernate.cache.use_second_level_cache", "false");
		extraProperties.put("hibernate.cache.use_structured_entries", "false");
		extraProperties.put("hibernate.cache.use_minimal_puts", "false");

		configureLuceneProperties(extraProperties, myFhirLuceneLocation);

		return extraProperties;
	}

	/**
	 * Bean which validates incoming requests
	 *
	 * @param theFhirInstanceValidator
	 */
	@Bean
	@Lazy
	public RequestValidatingInterceptor requestValidatingInterceptor(
			IInstanceValidatorModule theFhirInstanceValidator) {
		RequestValidatingInterceptor requestValidator = new RequestValidatingInterceptor();
		requestValidator.setFailOnSeverity(null);
		requestValidator.setAddResponseHeaderOnSeverity(null);
		requestValidator.setAddResponseOutcomeHeaderOnSeverity(ResultSeverityEnum.INFORMATION);
		requestValidator.addValidatorModule(theFhirInstanceValidator);
		requestValidator.setIgnoreValidatorExceptions(true);

		return requestValidator;
	}

	//	@Bean
	//	public IServerInterceptor subscriptionSecurityInterceptor() {
	//		return new SubscriptionsRequireManualActivationInterceptorDstu3();
	//	}

	@Bean
	@Primary
	public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
		JpaTransactionManager retVal = new JpaTransactionManager();
		retVal.setEntityManagerFactory(entityManagerFactory);
		return retVal;
	}

	/**
	 * This lets the "@Value" fields reference properties from the properties file
	 */
	@Bean
	public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
		return new PropertySourcesPlaceholderConfigurer();
	}
}
