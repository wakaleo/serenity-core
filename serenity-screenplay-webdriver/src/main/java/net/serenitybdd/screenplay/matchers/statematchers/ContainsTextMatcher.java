package net.serenitybdd.screenplay.matchers.statematchers;

import net.serenitybdd.core.pages.WebElementState;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

public class ContainsTextMatcher<T extends WebElementState> extends TypeSafeMatcher<T> {

    private final String expectedText;

    public ContainsTextMatcher(String expectedText) {
        this.expectedText = expectedText;
    }

    @Override
    protected boolean matchesSafely(T element) {
        return element.containsText(expectedText);
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("contains").appendText(expectedText);
    }
}