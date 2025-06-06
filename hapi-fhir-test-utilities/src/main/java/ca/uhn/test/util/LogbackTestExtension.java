/*-
 * #%L
 * HAPI FHIR Test Utilities
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
package ca.uhn.test.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Test helper to collect logback lines.
 * The empty constructor will capture all log events, or you can name a log root to limit the noise.
 */
public class LogbackTestExtension implements BeforeEachCallback, AfterEachCallback {
	private Logger myLogger;
	private final Level myLevel;
	private ListAppender<ILoggingEvent> myListAppender = null;
	private Level mySavedLevel;

	public LogbackTestExtension(Logger theLogger) {
		myLogger = theLogger;
		myLevel = null;
	}

	public LogbackTestExtension(Logger theLogger, Level theTestLogLevel) {
		myLogger = theLogger;
		myLevel = theTestLogLevel;
	}

	public LogbackTestExtension(String theLoggerName) {
		this((Logger) LoggerFactory.getLogger(theLoggerName));
	}

	public LogbackTestExtension() {
		this(org.slf4j.Logger.ROOT_LOGGER_NAME);
	}

	public LogbackTestExtension(String theLoggerName, Level theLevel) {
		this((Logger) LoggerFactory.getLogger(theLoggerName), theLevel);
	}

	public LogbackTestExtension(Class<?> theClass) {
		this(theClass.getName());
	}

	public LogbackTestExtension(Class<?> theClass, Level theLevel) {
		this(theClass.getName(), theLevel);
	}

	public LogbackTestExtension(org.slf4j.Logger theLogger) {
		this((Logger) theLogger);
	}

	/**
	 * Sets the root logger to the given level
	 */
	public LogbackTestExtension(Level theLevel) {
		this(org.slf4j.Logger.ROOT_LOGGER_NAME, theLevel);
	}

	public void setLoggerToWatch(String theLoggerName) {
		setLoggerToWatch((Logger)LoggerFactory.getLogger(theLoggerName));
	}

	public void setLoggerToWatch(Logger theLogger) {
		myLogger = theLogger;
	}

	public String getCurrentLogger() {
		return myLogger.getName();
	}

	/**
	 * Returns a copy to avoid concurrent modification errors.
	 * @return A copy of the log events so far.
	 */
	public List<ILoggingEvent> getLogEvents() {
		return new ArrayList<>(myListAppender.list);
	}

	public void clearEvents() {
		myListAppender.list.clear();
	}

	public ListAppender<ILoggingEvent> getAppender() {
		return myListAppender;
	}

	@Override
	public void beforeEach(ExtensionContext context) throws Exception {
		attachAppender();
	}

	private void attachAppender() {
		assert myListAppender == null;
		myListAppender = new ListAppender<>();
		myListAppender.start();
		myLogger.addAppender(myListAppender);

		mySavedLevel = myLogger.getLevel();
		setLoggerLevel(myLevel);
	}

	/**
	 * Temporarily set the logger level - It will be reset after the current test method is done
	 */
	public void setLoggerLevel(Level theLevel) {
		if (theLevel != null) {
			myLogger.setLevel(theLevel);
		}
	}

	/**
	 * @deprecated Use {@link #setLoggerLevel(Level)} instead
	 */
	@Deprecated
	public void setUp(Level theLevel) {
		setLoggerLevel(theLevel);
	}

	/**
	 * @deprecated This class should be registered as a junit5 extension, and will be set
	 * up automatically.
	 */
	@Deprecated
	public void setUp() {
		// nothing
	}

	@Override
	public void afterEach(ExtensionContext context) throws Exception {
		detachCurrentAppender();
	}

	private void detachCurrentAppender() {
		if (myListAppender != null) {
			myLogger.detachAppender(myListAppender);
			myListAppender.stop();
			myListAppender = null;
		}
		if (mySavedLevel != null) {
			myLogger.setLevel(mySavedLevel);
		}
	}

	public List<ILoggingEvent> getLogEvents(Predicate<ILoggingEvent> thePredicate) {
		return getLogEvents().stream().filter(thePredicate).toList();
	}

	@Nonnull
	public List<String> getLogMessages() {
		return getLogEvents().stream().map(ILoggingEvent::getMessage).toList();
	}

	public void reRegister() throws Exception {
		detachCurrentAppender();
		attachAppender();
	}

	/**
	 * Returns the first logged message where the formatted text (i.e. including placeholder {} substitutions) contains
	 * the substring of {@literal theText}.
	 */
	public Optional<ILoggingEvent> findLogEventWithFormattedMessage(String theText) {
		for (ILoggingEvent t : getLogEvents()) {
			String formattedMessage = t.getFormattedMessage();
			if (formattedMessage.contains(theText)) {
				return Optional.of(t);
			}
		}
		return Optional.empty();
	}

    /**
	 * Predicate for passing to {@link #getLogEvents(Predicate)}
	 */
	public static Predicate<ILoggingEvent> atLeastLevel(Level theLevel) {
		return e -> e.getLevel().isGreaterOrEqual(theLevel);
	}

}
