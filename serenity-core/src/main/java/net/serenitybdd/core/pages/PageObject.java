package net.serenitybdd.core.pages;

import ch.lambdaj.function.convert.Converter;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.annotations.WhenPageOpens;
import net.thucydides.core.fluent.ThucydidesFluentAdapter;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.pages.Pages;
import net.thucydides.core.pages.WrongPageError;
import net.thucydides.core.pages.components.Dropdown;
import net.thucydides.core.pages.components.FileToUpload;
import net.thucydides.core.pages.jquery.JQueryEnabledPage;
import net.thucydides.core.reflection.MethodFinder;
import net.thucydides.core.scheduling.FluentWaitWithRefresh;
import net.thucydides.core.scheduling.NormalFluentWait;
import net.thucydides.core.scheduling.ThucydidesFluentWait;
import net.thucydides.core.steps.PageObjectStepDelayer;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.core.steps.WaitForBuilder;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.util.Inflector;
import net.thucydides.core.webdriver.*;
import net.thucydides.core.webdriver.javascript.JavascriptExecutorFacade;
import net.thucydides.core.webelements.Checkbox;
import net.thucydides.core.webelements.RadioButtonGroup;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static ch.lambdaj.Lambda.convert;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.serenitybdd.core.selectors.Selectors.xpathOrCssSelector;
import static net.thucydides.core.ThucydidesSystemProperty.THUCYDIDES_JQUERY_INTEGRATION;
import static net.thucydides.core.webdriver.javascript.JavascriptSupport.javascriptIsSupportedIn;

/**
 * A base class representing a WebDriver page object.
 *
 * @author johnsmart
 */
public abstract class PageObject {


    private static final int WAIT_FOR_ELEMENT_PAUSE_LENGTH = 250;

    private static final Logger LOGGER = LoggerFactory.getLogger(PageObject.class);

    private WebDriver driver;

    private Pages pages;

    private MatchingPageExpressions matchingPageExpressions;

    private RenderedPageObjectView renderedView;

    private PageUrls pageUrls;

    private net.serenitybdd.core.time.SystemClock clock;

    private Duration waitForTimeout;
    private Duration waitForElementTimeout;

    private final Sleeper sleeper;
    private final Clock webdriverClock;
    private JavascriptExecutorFacade javascriptExecutorFacade;

    private EnvironmentVariables environmentVariables;

    public void setImplicitTimeout(int duration, TimeUnit unit) {
        waitForElementTimeout = new Duration(duration, unit);
        setDriverImplicitTimeout(waitForElementTimeout);
    }

    private void setDriverImplicitTimeout(Duration implicitTimeout) {
        if (driver instanceof ConfigurableTimeouts) {
            ((ConfigurableTimeouts) driver).setImplicitTimeout(implicitTimeout);
        } else {
            driver.manage().timeouts().implicitlyWait(implicitTimeout.in(MILLISECONDS), MILLISECONDS);
        }
    }

    public void resetImplicitTimeout() {
        if (driver instanceof ConfigurableTimeouts) {
            waitForElementTimeout = ((ConfigurableTimeouts) driver).resetTimeouts();
        } else {
            waitForElementTimeout = getDefaultImplicitTimeout();
            driver.manage().timeouts().implicitlyWait(waitForElementTimeout.in(MILLISECONDS), MILLISECONDS);
        }
    }
    private Duration getDefaultImplicitTimeout() {
        Integer configuredTimeout = ThucydidesSystemProperty.WEBDRIVER_TIMEOUTS_IMPLICITLYWAIT.integerFrom(environmentVariables);
        return new Duration(configuredTimeout, TimeUnit.MILLISECONDS);

    }

    private enum OpenMode {
        CHECK_URL_PATTERNS,
        IGNORE_URL_PATTERNS
    }

    protected PageObject() {
        this.webdriverClock = new SystemClock();
        this.clock = Injectors.getInjector().getInstance(net.serenitybdd.core.time.SystemClock.class);
        this.environmentVariables = Injectors.getInjector().getProvider(EnvironmentVariables.class).get();
        this.sleeper = Sleeper.SYSTEM_SLEEPER;
        setupPageUrls();
    }

    protected PageObject(final WebDriver driver, Predicate<? super PageObject> callback) {
        this();
        this.driver = driver;
        callback.apply(this);
    }

    public PageObject(final WebDriver driver, final int ajaxTimeout) {
        this();
        setDriver(driver, ajaxTimeout);
    }

    public PageObject(final WebDriver driver) {
        this();
        ThucydidesWebDriverSupport.useDriver(driver);
        setDriver(driver);
    }

    public PageObject(final WebDriver driver, final EnvironmentVariables environmentVariables) {
        this();
        this.environmentVariables = environmentVariables;
        setDriver(driver);
    }

    protected void setDriver(WebDriver driver, long timeout) {
        this.driver = driver;
        new DefaultPageObjectInitialiser(driver, timeout).apply(this);
    }

    public void setDriver(WebDriver driver) {
        setDriver(driver, getImplicitWaitTimeout().in(TimeUnit.MILLISECONDS));
    }

    public PageObject withDriver(WebDriver driver) {
        setDriver(driver);
        return this;
    }

    public Duration getWaitForTimeout() {

        if (waitForTimeout == null) {
            int configuredWaitForTimeoutInMilliseconds =
                    ThucydidesSystemProperty.WEBDRIVER_WAIT_FOR_TIMEOUT
                            .integerFrom(environmentVariables, (int) DefaultTimeouts.DEFAULT_WAIT_FOR_TIMEOUT.in(MILLISECONDS));
            waitForTimeout = new Duration(configuredWaitForTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
        }
        return waitForTimeout;
    }

    @Deprecated
    public Duration getWaitForElementTimeout() {
        return getImplicitWaitTimeout();
    }

    public Duration getImplicitWaitTimeout() {

        if (waitForElementTimeout == null) {
            int configuredWaitForTimeoutInMilliseconds =
                    ThucydidesSystemProperty.WEBDRIVER_TIMEOUTS_IMPLICITLYWAIT
                            .integerFrom(environmentVariables, (int) DefaultTimeouts.DEFAULT_IMPLICIT_WAIT_TIMEOUT.in(MILLISECONDS));
            waitForElementTimeout = new Duration(configuredWaitForTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
        }
        return waitForElementTimeout;
    }

    public void setPages(Pages pages) {
        this.pages = pages;
    }

    public <T extends PageObject> T switchToPage(final Class<T> pageObjectClass) {
        if (pages.getDriver() == null) {
            pages.setDriver(driver);
        }

        return pages.getPage(pageObjectClass);
    }

    public FileToUpload upload(final String filename) {
        return new FileToUpload(filename).useRemoteDriver(isDefinedRemoteUrl());
    }

    public FileToUpload uploadData(String data) throws IOException {
        Path datafile = Files.createTempFile("upload", "data");
        Files.write(datafile, data.getBytes(StandardCharsets.UTF_8));
        return new FileToUpload(datafile.toAbsolutePath().toString()).useRemoteDriver(isDefinedRemoteUrl());
    }

    public FileToUpload uploadData(byte[] data) throws IOException {
        Path datafile = Files.createTempFile("upload", "data");
        Files.write(datafile, data);
        return new FileToUpload(datafile.toAbsolutePath().toString()).useRemoteDriver(isDefinedRemoteUrl());
    }

    private boolean isDefinedRemoteUrl() {
        boolean isRemoteUrl = ThucydidesSystemProperty.WEBDRIVER_REMOTE_URL.isDefinedIn(pages.getConfiguration().getEnvironmentVariables());
        boolean isSaucelabsUrl = ThucydidesSystemProperty.SAUCELABS_URL.isDefinedIn(pages.getConfiguration().getEnvironmentVariables());
        boolean isBrowserStack = ThucydidesSystemProperty.BROWSERSTACK_URL.isDefinedIn(pages.getConfiguration().getEnvironmentVariables());
        return isRemoteUrl || isSaucelabsUrl || isBrowserStack;
    }

    private void setupPageUrls() {
        setPageUrls(new PageUrls(this));
    }

    /**
     * Only for testing purposes.
     */
    public void setPageUrls(PageUrls pageUrls) {
        this.pageUrls = pageUrls;
    }

    public void setWaitForTimeout(final long waitForTimeoutInMilliseconds) {
        this.waitForTimeout = new Duration(waitForTimeoutInMilliseconds, MILLISECONDS);
        getRenderedView().setWaitForTimeout(this.waitForTimeout);
    }

    public void setWaitForElementTimeout(final long waitForTimeoutInMilliseconds) {
        this.waitForElementTimeout = new Duration(waitForTimeoutInMilliseconds, MILLISECONDS);
    }

    protected RenderedPageObjectView getRenderedView() {
        if (renderedView == null) {
            renderedView = new RenderedPageObjectView(driver, this, getWaitForTimeout(), true);
        }
        return renderedView;
    }

    protected net.serenitybdd.core.time.SystemClock getClock() {
        return clock;
    }

    private MatchingPageExpressions getMatchingPageExpressions() {
        if (matchingPageExpressions == null) {
            matchingPageExpressions = new MatchingPageExpressions(this);
        }
        return matchingPageExpressions;
    }

    public WebDriver getDriver() {
        return driver;
    }

    public String getTitle() {
        return driver.getTitle();
    }


    public boolean matchesAnyUrl() {
        return thereAreNoPatternsDefined();
    }

    /**
     * Does this page object work for this URL? When matching a URL, we check
     * with and without trailing slashes
     */
    public final boolean compatibleWithUrl(final String currentUrl) {
        return thereAreNoPatternsDefined() || matchUrlAgainstEachPattern(currentUrl);
    }

    private boolean matchUrlAgainstEachPattern(final String currentUrl) {
        return getMatchingPageExpressions().matchUrlAgainstEachPattern(currentUrl);
    }

    private boolean thereAreNoPatternsDefined() {
        return getMatchingPageExpressions().isEmpty();
    }

    public PageObject waitForRenderedElements(final By byElementCriteria) {
        getRenderedView().waitFor(byElementCriteria);
        return this;
    }

    public RenderedPageObjectView withTimeoutOf(int timeout, TimeUnit units) {
        return withTimeoutOf(new Duration(timeout, units));
    }

    public RenderedPageObjectView withTimeoutOf(Duration timeout) {
        return new RenderedPageObjectView(driver, this, timeout, false);
    }

    public PageObject waitFor(String xpathOrCssSelector) {
        return waitForRenderedElements(xpathOrCssSelector(xpathOrCssSelector));
    }

    public PageObject waitFor(ExpectedCondition expectedCondition) {
        getRenderedView().waitFor(expectedCondition);
        return this;
    }

    public PageObject waitForRenderedElementsToBePresent(final By byElementCriteria) {
        getRenderedView().waitForPresenceOf(byElementCriteria);

        return this;
    }

    public PageObject waitForPresenceOf(String xpathOrCssSelector) {
        return waitForRenderedElementsToBePresent(xpathOrCssSelector(xpathOrCssSelector));
    }


    public PageObject waitForRenderedElementsToDisappear(final By byElementCriteria) {
        getRenderedView().waitForElementsToDisappear(byElementCriteria);
        return this;
    }

    public PageObject waitForAbsenceOf(String xpathOrCssSelector) {
        return waitForRenderedElementsToDisappear(xpathOrCssSelector(xpathOrCssSelector));
    }

    /**
     * Waits for a given text to appear anywhere on the page.
     */
    public PageObject waitForTextToAppear(final String expectedText) {
        getRenderedView().waitForText(expectedText);
        return this;
    }

    public PageObject waitForTitleToAppear(final String expectedTitle) {
        waitOnPage().until(ExpectedConditions.titleIs(expectedTitle));
        return this;
    }

    private WebDriverWait waitOnPage() {
        return new WebDriverWait(driver, getWaitForTimeout().in(TimeUnit.SECONDS));
//        waitForTimeoutInSecondsWithAMinimumOfOneSecond());
    }

    public PageObject waitForTitleToDisappear(final String expectedTitle) {
        getRenderedView().waitForTitleToDisappear(expectedTitle);
        return this;
    }

    /**
     * Waits for a given text to appear inside the element.
     */
    public PageObject waitForTextToAppear(final WebElement element,
                                          final String expectedText) {
        getRenderedView().waitForText(element, expectedText);
        return this;
    }

    private boolean driverIsDisabled() {
        return StepEventBus.getEventBus().webdriverCallsAreSuspended();
    }

    /**
     * Waits for a given text to disappear from the element.
     */
    public PageObject waitForTextToDisappear(final WebElement element,
                                             final String expectedText) {
        if (!driverIsDisabled()) {
            waitForCondition().until(elementDoesNotContain(element, expectedText));
        }
        return this;
    }


    private ExpectedCondition<Boolean> elementDoesNotContain(final WebElement element, final String expectedText) {
        return new ExpectedCondition<Boolean>() {
            public Boolean apply(WebDriver driver) {
                return !element.getText().contains(expectedText);
            }
        };
    }

    public PageObject waitForTextToDisappear(final String expectedText) {
        return waitForTextToDisappear(expectedText, getWaitForTimeout().in(MILLISECONDS));
    }

    /**
     * Waits for a given text to not be anywhere on the page.
     */
    public PageObject waitForTextToDisappear(final String expectedText,
                                             final long timeoutInMilliseconds) {

        getRenderedView().waitForTextToDisappear(expectedText, timeoutInMilliseconds);
        return this;
    }

    /**
     * Waits for a given text to appear anywhere on the page.
     */
    public PageObject waitForTextToAppear(final String expectedText,
                                          final long timeout) {

        getRenderedView().waitForTextToAppear(expectedText, timeout);
        return this;
    }

    /**
     * Waits for any of a number of text blocks to appear anywhere on the
     * screen.
     */
    public PageObject waitForAnyTextToAppear(final String... expectedText) {
        getRenderedView().waitForAnyTextToAppear(expectedText);
        return this;
    }

    public PageObject waitForAnyTextToAppear(final WebElement element,
                                             final String... expectedText) {
        getRenderedView().waitForAnyTextToAppear(element, expectedText);
        return this;
    }

    /**
     * Waits for all of a number of text blocks to appear on the screen.
     */
    public PageObject waitForAllTextToAppear(final String... expectedTexts) {
        getRenderedView().waitForAllTextToAppear(expectedTexts);
        return this;
    }

    public PageObject waitForAnyRenderedElementOf(final By... expectedElements) {
        getRenderedView().waitForAnyRenderedElementOf(expectedElements);
        return this;
    }

    protected void waitABit(final long timeInMilliseconds) {
        getClock().pauseFor(timeInMilliseconds);
    }

    public WaitForBuilder<? extends PageObject> waitFor(int duration) {
        return new PageObjectStepDelayer(clock, this).waitFor(duration);
    }

    public List<WebElement> thenReturnElementList(final By byListCriteria) {
        return driver.findElements(byListCriteria);
    }

    public <T extends PageObject> T foo() {
        return (T) this;
    }

    /**
     * Check that the specified text appears somewhere in the page.
     */
    public void shouldContainText(final String textValue) {
        if (!containsText(textValue)) {
            String errorMessage = String.format(
                    "The text '%s' was not found in the page", textValue);
            throw new NoSuchElementException(errorMessage);
        }
    }

    /**
     * Check that all of the specified texts appears somewhere in the page.
     */
    public void shouldContainAllText(final String... textValues) {
        if (!containsAllText(textValues)) {
            String errorMessage = String.format(
                    "One of the text elements in '%s' was not found in the page", (Object[]) textValues);
            throw new NoSuchElementException(errorMessage);
        }
    }

    /**
     * Does the specified web element contain a given text value. Useful for dropdowns and so on.
     *
     * @deprecated use element(webElement).containsText(textValue)
     */
    @Deprecated
    public boolean containsTextInElement(final WebElement webElement, final String textValue) {
        return element(webElement).containsText(textValue);
    }

    /*
     * Check that the element contains a given text.
     * @deprecated use element(webElement).shouldContainText(textValue)
     */
    @Deprecated
    public void shouldContainTextInElement(final WebElement webElement, final String textValue) {
        element(webElement).shouldContainText(textValue);
    }

    /*
     * Check that the element does not contain a given text.
     * @deprecated use element(webElement).shouldNotContainText(textValue)
     */
    @Deprecated
    public void shouldNotContainTextInElement(final WebElement webElement, final String textValue) {
        element(webElement).shouldNotContainText(textValue);
    }

    /**
     * Clear a field and enter a value into it.
     */
    public void typeInto(final WebElement field, final String value) {
        element(field).type(value);
    }

    /**
     * Clear a field and enter a value into it.
     * This is a more fluent alternative to using the typeInto method.
     */
    public FieldEntry enter(final String value) {
        return new FieldEntry(value);
    }

    public void selectFromDropdown(final WebElement dropdown,
                                   final String visibleLabel) {

        Dropdown.forWebElement(dropdown).select(visibleLabel);
        notifyScreenChange();
    }

    public void selectMultipleItemsFromDropdown(final WebElement dropdown,
                                                final String... selectedLabels) {
        Dropdown.forWebElement(dropdown).selectMultipleItems(selectedLabels);
        notifyScreenChange();
    }


    public Set<String> getSelectedOptionLabelsFrom(final WebElement dropdown) {
        return Dropdown.forWebElement(dropdown).getSelectedOptionLabels();
    }

    public Set<String> getSelectedOptionValuesFrom(final WebElement dropdown) {
        return Dropdown.forWebElement(dropdown).getSelectedOptionValues();
    }

    public String getSelectedValueFrom(final WebElement dropdown) {
        return Dropdown.forWebElement(dropdown).getSelectedValue();
    }

    public String getSelectedLabelFrom(final WebElement dropdown) {
        return Dropdown.forWebElement(dropdown).getSelectedLabel();
    }

    public void setCheckbox(final WebElement field, final boolean value) {
        Checkbox checkbox = new Checkbox(field);
        checkbox.setChecked(value);
        notifyScreenChange();
    }

    public boolean containsText(final String textValue) {
        return getRenderedView().containsText(textValue);
    }

    /**
     * Check that the specified text appears somewhere in the page.
     */
    public boolean containsAllText(final String... textValues) {
        for (String textValue : textValues) {
            if (!getRenderedView().containsText(textValue)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fail the test if this element is not displayed (rendered) on the screen.
     */
    public void shouldBeVisible(final WebElement field) {
        element(field).shouldBeVisible();
    }

    public void shouldBeVisible(final By byCriteria) {
        waitOnPage().until(ExpectedConditions.visibilityOfElementLocated(byCriteria));
    }

    public void shouldNotBeVisible(final WebElement field) {
        try {
            element(field).shouldNotBeVisible();
        } catch (NoSuchElementException e) {
            // A non-existant element is not visible
        }
    }

    public void shouldNotBeVisible(final By byCriteria) {
        List<WebElement> matchingElements = getDriver().findElements(byCriteria);
        if (!matchingElements.isEmpty()) {
            waitOnPage().until(ExpectedConditions.invisibilityOfElementLocated(byCriteria));
        }
    }

    private long waitForTimeoutInSecondsWithAMinimumOfOneSecond() {
        return (getWaitForTimeout().in(TimeUnit.SECONDS) < 1) ? 1 : (getWaitForTimeout().in(TimeUnit.SECONDS));
    }

    public long waitForTimeoutInMilliseconds() {
        return getWaitForTimeout().in(MILLISECONDS);
    }

    public long implicitTimoutMilliseconds() {
        return getImplicitWaitTimeout().in(MILLISECONDS);
    }

    public String updateUrlWithBaseUrlIfDefined(final String startingUrl) {

        String baseUrl = pageUrls.getSystemBaseUrl();
        if ((baseUrl != null) && (!StringUtils.isEmpty(baseUrl))) {
            return replaceHost(startingUrl, baseUrl);
        } else {
            return startingUrl;
        }
    }

    private String replaceHost(final String starting, final String base) {

        String updatedUrl = starting;
        try {
            URL startingUrl = new URL(starting);
            URL baseUrl = new URL(base);

            String startingHostComponent = hostComponentFrom(startingUrl.getProtocol(),
                    startingUrl.getHost(),
                    startingUrl.getPort());
            String baseHostComponent = hostComponentFrom(baseUrl.getProtocol(),
                    baseUrl.getHost(),
                    baseUrl.getPort());
            updatedUrl = starting.replaceFirst(startingHostComponent, baseHostComponent);
        } catch (MalformedURLException e) {
            LOGGER.error("Failed to analyse default page URL: Starting URL: {}, Base URL: {}", starting, base);
            LOGGER.error("URL analysis failed with exception:", e);
        }

        return updatedUrl;
    }

    private String hostComponentFrom(final String protocol, final String host, final int port) {
        StringBuilder hostComponent = new StringBuilder(protocol);
        hostComponent.append("://");
        hostComponent.append(host);
        if (port > 0) {
            hostComponent.append(":");
            hostComponent.append(port);
        }
        return hostComponent.toString();
    }

    /**
     * Open the webdriver browser using a paramaterized URL. Parameters are
     * represented in the URL using {0}, {1}, etc.
     */
    public final void open(final String[] parameterValues) {
        open(OpenMode.CHECK_URL_PATTERNS, parameterValues);
    }

    /**
     * Opens page without checking URL patterns. Same as open(String...)) otherwise.
     */
    public final void openUnchecked(final String... parameterValues) {
        open(OpenMode.IGNORE_URL_PATTERNS, parameterValues);
    }

    private void open(final OpenMode openMode, final String... parameterValues) {
        String startingUrl = pageUrls.getStartingUrl(parameterValues);
        LOGGER.debug("Opening page at url {}", startingUrl);
        openPageAtUrl(startingUrl);
        checkUrlPatterns(openMode);
        initializePage();
        LOGGER.debug("Page opened");
    }

    public final void open(final String urlTemplateName,
                           final String[] parameterValues) {
        open(OpenMode.CHECK_URL_PATTERNS, urlTemplateName, parameterValues);
    }

    /**
     * Opens page without checking URL patterns. Same as {@link #open(String, String[])} otherwise.
     */
    public final void openUnchecked(final String urlTemplateName,
                                    final String[] parameterValues) {
        open(OpenMode.IGNORE_URL_PATTERNS, urlTemplateName, parameterValues);
    }

    private void open(final OpenMode openMode, final String urlTemplateName,
                      final String[] parameterValues) {
        String startingUrl = pageUrls.getNamedUrl(urlTemplateName,
                parameterValues);
        LOGGER.debug("Opening page at url {}", startingUrl);
        openPageAtUrl(startingUrl);
        checkUrlPatterns(openMode);
        initializePage();
        LOGGER.debug("Page opened");
    }

    /**
     * Open the webdriver browser to the base URL, determined by the DefaultUrl
     * annotation if present. If the DefaultUrl annotation is not present, the
     * default base URL will be used. If the DefaultUrl annotation is present, a
     * URL based on the current base url from the system-wide default url
     * and the relative path provided in the DefaultUrl annotation will be used to
     * determine the URL to open. For example, consider the following class:
     * <pre>
     *     <code>
     *         &#064;DefaultUrl("http://localhost:8080/client/list")
     *         public class ClientList extends PageObject {
     *             ...
     *
     *             &#064;WhenPageOpens
     *             public void waitUntilTitleAppears() {...}
     *         }
     *     </code>
     * </pre>
     * Suppose you are using a base URL of http://stage.acme.com. When you call open() for this class,
     * it will open http://stage.acme.com/client/list. It will then invoke the waitUntilTitleAppears() method.
     */
    final public void open() {
        open(OpenMode.CHECK_URL_PATTERNS);
    }

    /**
     * Opens page without checking URL patterns. Same as {@link #open()} otherwise.
     */
    final public void openUnchecked() {
        open(OpenMode.IGNORE_URL_PATTERNS);
    }

    private void open(final OpenMode openMode) {
        String startingUrl = updateUrlWithBaseUrlIfDefined(pageUrls.getStartingUrl());
        openPageAtUrl(startingUrl);
        checkUrlPatterns(openMode);
        initializePage();
    }

    private void initializePage() {
        addJQuerySupport();
        callWhenPageOpensMethods();
    }

    private void checkUrlPatterns(final OpenMode openMode) {
        if (openMode == OpenMode.CHECK_URL_PATTERNS) {
            ensurePageIsOnAMatchingUrl();
        }
    }

    private void ensurePageIsOnAMatchingUrl() {
        if (!matchesAnyUrl()) {
            String currentUrl = getDriver().getCurrentUrl();
            if (!compatibleWithUrl(currentUrl)) {
                thisIsNotThePageYourLookingFor();
            }
        }
    }

    /**
     * Use the @At annotation (if present) to check that a page object is displaying the correct page.
     * Will throw an exception if the current URL does not match the expected one.
     */
    public void shouldBeDisplayed() {
        ensurePageIsOnAMatchingUrl();
    }

    private void thisIsNotThePageYourLookingFor() {

        String errorDetails = "This is not the page you're looking for: "
                + "I was looking for a page compatible with " + this.getClass() + " but "
                + "I was at the URL " + getDriver().getCurrentUrl();

        throw new WrongPageError(errorDetails);
    }

    final public void openAt(String startingUrl) {
        openPageAtUrl(updateUrlWithBaseUrlIfDefined(startingUrl));
        callWhenPageOpensMethods();
    }

    /**
     * Override this method
     */
    public void callWhenPageOpensMethods() {
        for (Method annotatedMethod : methodsAnnotatedWithWhenPageOpens()) {
            try {
                annotatedMethod.setAccessible(true);
                annotatedMethod.invoke(this);
            } catch (Throwable e) {
                LOGGER.error("Could not execute @WhenPageOpens annotated method: " + e.getMessage());
                if (e instanceof InvocationTargetException) {
                    e = ((InvocationTargetException) e).getTargetException();
                }
                if (AssertionError.class.isAssignableFrom(e.getClass())) {
                    throw (AssertionError) e;
                } else {
                    throw new UnableToInvokeWhenPageOpensMethods("Could not execute @WhenPageOpens annotated method: "
                            + e.getMessage(), e);
                }
            }
        }
    }

    private List<Method> methodsAnnotatedWithWhenPageOpens() {
        List<Method> methods = MethodFinder.inClass(this.getClass()).getAllMethods();
        List<Method> annotatedMethods = new ArrayList<>();
        for (Method method : methods) {
            if (method.getAnnotation(WhenPageOpens.class) != null) {
                if (method.getParameterTypes().length == 0) {
                    annotatedMethods.add(method);
                } else {
                    throw new UnableToInvokeWhenPageOpensMethods("Could not execute @WhenPageOpens annotated method: WhenPageOpens method cannot have parameters: " + method);
                }
            }
        }
        return annotatedMethods;
    }

    public static String[] withParameters(final String... parameterValues) {
        return parameterValues;
    }

    private void openPageAtUrl(final String startingUrl) {
        getDriver().get(startingUrl);
        if (javascriptIsSupportedIn(getDriver())) {
            addJQuerySupport();
        }
    }

    public void clickOn(final WebElement webElement) {
        element(webElement).click();
    }

    /**
     * Returns true if at least one matching element is found on the page and is visible.
     */
    public Boolean isElementVisible(final By byCriteria) {
        return getRenderedView().elementIsDisplayed(byCriteria);
    }

    public void setDefaultBaseUrl(final String defaultBaseUrl) {
        pageUrls.overrideDefaultBaseUrl(defaultBaseUrl);
    }

    /**
     * Returns true if the specified element has the focus.
     *
     * @deprecated Use element(webElement).hasFocus() instead
     */
    public boolean hasFocus(final WebElement webElement) {
        return element(webElement).hasFocus();
    }

    public void blurActiveElement() {
        getJavascriptExecutorFacade().executeScript("document.activeElement.blur();");
    }

    protected JavascriptExecutorFacade getJavascriptExecutorFacade() {
        if (javascriptExecutorFacade == null) {
            javascriptExecutorFacade = new JavascriptExecutorFacade(driver);
        }
        return javascriptExecutorFacade;
    }

    /**
     * Provides a fluent API for querying web elements.
     */
    public <T extends net.serenitybdd.core.pages.WebElementFacade> T element(WebElement webElement) {
        return net.serenitybdd.core.pages.WebElementFacadeImpl.wrapWebElement(driver, webElement,
                getImplicitWaitTimeout().in(MILLISECONDS),
                getWaitForTimeout().in(MILLISECONDS),
                nameOf(webElement));
    }

    private String nameOf(WebElement webElement) {
        try {
            return webElement.toString();
        } catch (Exception e) {
            return "Unknown web element";
        }
    }


    public <T extends net.serenitybdd.core.pages.WebElementFacade> T $(WebElement webElement) {
        return element(webElement);
    }

    public <T extends net.serenitybdd.core.pages.WebElementFacade> T $(String xpathOrCssSelector) {
        return element(xpathOrCssSelector);
    }

    /**
     * Provides a fluent API for querying web elements.
     */
    public <T extends net.serenitybdd.core.pages.WebElementFacade> T element(By bySelector) {
        return net.serenitybdd.core.pages.WebElementFacadeImpl.wrapWebElement(driver,
                bySelector,
                getImplicitWaitTimeout().in(MILLISECONDS),
                getWaitForTimeout().in(MILLISECONDS),
                bySelector.toString());
    }

    public <T extends net.serenitybdd.core.pages.WebElementFacade> T find(List<By> selectors) {
        T element = null;
        for (By selector : selectors) {
            if (element == null) {
                element = element(selector);
            } else {
                element = element.find(selector);
            }
        }
        return element;
    }

    public <T extends net.serenitybdd.core.pages.WebElementFacade> T find(By... selectors) {
        return find(Lists.newArrayList(selectors));
    }

    public List<net.serenitybdd.core.pages.WebElementFacade> findAll(By bySelector) {
        List<WebElement> matchingWebElements = driver.findElements(bySelector);
        return convert(matchingWebElements, toWebElementFacades());
    }

    private Converter<WebElement, net.serenitybdd.core.pages.WebElementFacade> toWebElementFacades() {
        return new Converter<WebElement, net.serenitybdd.core.pages.WebElementFacade>() {
            public net.serenitybdd.core.pages.WebElementFacade convert(WebElement from) {
                return element(from);
            }
        };
    }

    /**
     * Provides a fluent API for querying web elements.
     */
    public <T extends net.serenitybdd.core.pages.WebElementFacade> T element(String xpathOrCssSelector) {
        return element(xpathOrCssSelector(xpathOrCssSelector));
    }

    public <T extends net.serenitybdd.core.pages.WebElementFacade> T findBy(String xpathOrCssSelector) {
        return element(xpathOrCssSelector);
    }

    public List<net.serenitybdd.core.pages.WebElementFacade> findAll(String xpathOrCssSelector) {
        return findAll(xpathOrCssSelector(xpathOrCssSelector));
    }

    public boolean containsElements(By bySelector) {
        return !findAll(bySelector).isEmpty();
    }

    public boolean containsElements(String xpathOrCssSelector) {
        return !findAll(xpathOrCssSelector).isEmpty();
    }


    public Object evaluateJavascript(final String script) {
        addJQuerySupport();
        JavascriptExecutorFacade js = new JavascriptExecutorFacade(driver);
        return js.executeScript(script);
    }

    public Object evaluateJavascript(final String script, final Object... params) {
        addJQuerySupport();
        JavascriptExecutorFacade js = new JavascriptExecutorFacade(driver);
        return js.executeScript(script, params);
    }

    public void addJQuerySupport() {
        if (pageIsLoaded() && jqueryIntegrationIsActivated() && driverIsJQueryCompatible()) {
            JQueryEnabledPage jQueryEnabledPage = JQueryEnabledPage.withDriver(getDriver());
            jQueryEnabledPage.activateJQuery();
        }
    }

    protected boolean driverIsJQueryCompatible() {
        try {
            if (getDriver() instanceof WebDriverFacade) {
                return SupportedWebDriver.forClass(((WebDriverFacade) getDriver()).getDriverClass())
                        .supportsJavascriptInjection();
            }
            return SupportedWebDriver.forClass(getDriver().getClass()).supportsJavascriptInjection();
        } catch (IllegalArgumentException probablyAMockedDriver) {
            return false;
        }
    }

    private Boolean jqueryIntegrationIsActivated() {
        return THUCYDIDES_JQUERY_INTEGRATION.booleanFrom(environmentVariables, true);
    }

    public RadioButtonGroup inRadioButtonGroup(String name) {
        return new RadioButtonGroup(getDriver().findElements(By.name(name)));
    }

    private boolean pageIsLoaded() {
        try {
            return (driverIsInstantiated() && getDriver().getCurrentUrl() != null);
        } catch (WebDriverException e) {
            return false;
        }
    }

    protected boolean driverIsInstantiated() {
        if (getDriver() instanceof WebDriverFacade) {
            return ((WebDriverFacade) getDriver()).isEnabled() && ((WebDriverFacade) getDriver()).isInstantiated();
        }
        return true;
    }

    public ThucydidesFluentWait<WebDriver> waitForWithRefresh() {
        return new FluentWaitWithRefresh<>(driver, webdriverClock, sleeper)
                .withTimeout(getWaitForTimeout().in(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
                .pollingEvery(WAIT_FOR_ELEMENT_PAUSE_LENGTH, TimeUnit.MILLISECONDS)
                .ignoring(NoSuchElementException.class, NoSuchFrameException.class);
    }

    public ThucydidesFluentWait<WebDriver> waitForCondition() {
        return new NormalFluentWait<>(driver, webdriverClock, sleeper)
                .withTimeout(getWaitForTimeout().in(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
                .pollingEvery(WAIT_FOR_ELEMENT_PAUSE_LENGTH, TimeUnit.MILLISECONDS)
                .ignoring(NoSuchElementException.class, NoSuchFrameException.class);
    }

    public WebElementFacade waitFor(WebElement webElement) {
        return getRenderedView().waitFor(webElement);
    }

    public WebElementFacade waitFor(WebElementFacade webElement) {
        return getRenderedView().waitFor(webElement);
    }


    public Alert getAlert() {
        return driver.switchTo().alert();
    }

    public Actions withAction() {
        WebDriver proxiedDriver = ((WebDriverFacade) getDriver()).getProxiedDriver();
        return new Actions(proxiedDriver);
    }

    public class FieldEntry {

        private final String value;

        public FieldEntry(final String value) {
            this.value = value;
        }

        public void into(final WebElement field) {
            element(field).type(value);
        }

        public void into(final net.serenitybdd.core.pages.WebElementFacade field) {
            field.type(value);
        }

        public void intoField(final By bySelector) {
            WebElement field = getDriver().findElement(bySelector);
            into(field);
        }
    }

    private void notifyScreenChange() {
        StepEventBus.getEventBus().notifyScreenChange();
    }

    protected ThucydidesFluentAdapter fluent() {
        return new ThucydidesFluentAdapter(getDriver());
    }

    public <T extends WebElementFacade> T moveTo(String xpathOrCssSelector) {
        if (!driverIsDisabled()) {
            withAction().moveToElement(findBy(xpathOrCssSelector)).perform();
        }
        return findBy(xpathOrCssSelector);
    }

    public <T extends WebElementFacade> T moveTo(By locator) {
        if (!driverIsDisabled()) {
            withAction().moveToElement(find(locator)).perform();
        }
        return find(locator);
    }

    public void waitForAngularRequestsToFinish() {
        if ((boolean) getJavascriptExecutorFacade().executeScript(
                "return (typeof angular !== 'undefined')? true : false;")) {
            getJavascriptExecutorFacade()
                    .executeAsyncScript(
                            "var callback = arguments[arguments.length - 1];"
                                    + "angular.element(document.body).injector().get('$browser').notifyWhenNoOutstandingRequests(callback);");
        }
    }

    Inflector inflection = Inflector.getInstance();

    @Override
    public String toString() {
        return inflection.of(getClass().getSimpleName())
                .inHumanReadableForm().toString();
    }
}
