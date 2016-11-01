package net.serenitybdd.screenplay.targets;

import net.serenitybdd.core.pages.WebElementFacade;
import net.serenitybdd.screenplay.Actor;

import java.util.List;

import static net.serenitybdd.screenplay.targets.EnsureFieldVisible.ensureThat;

public class XPathOrCssTarget extends Target {

    private final String cssOrXPathSelector;

    public XPathOrCssTarget(String targetElementName, String cssOrXPathSelector, IFrame iFrame) {
        super(targetElementName, iFrame);
        this.cssOrXPathSelector = cssOrXPathSelector;
    }

    public WebElementFacade resolveFor(Actor theActor) {
        TargetResolver resolver = TargetResolver.switchIFrameIfRequired(theActor, this);
        WebElementFacade resolvedTarget = resolver.findBy(cssOrXPathSelector);
        ensureThat(theActor).canSee(resolvedTarget);
        return resolvedTarget;
    }

    public List<WebElementFacade> resolveAllFor(Actor theActor) {
        TargetResolver resolver = TargetResolver.switchIFrameIfRequired(theActor, this);
        return resolver.findAll(cssOrXPathSelector);
    }

    public Target of(String... parameters) {
        return new XPathOrCssTarget(targetElementName, instantiated(cssOrXPathSelector, parameters), iFrame);
    }

    public Target called(String name) {
        return new XPathOrCssTarget(name, cssOrXPathSelector, iFrame);
    }

    public String getCssOrXPathSelector() {
        return cssOrXPathSelector;
    }

    @Override
    public IFrame getIFrame() {
        return iFrame;
    }

    private String instantiated(String cssOrXPathSelector, String[] parameters) {
        return new TargetSelectorWithVariables(cssOrXPathSelector).resolvedWith(parameters);
    }
}
