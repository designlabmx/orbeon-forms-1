/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.action;

import org.apache.commons.lang3.StringUtils;
import org.orbeon.dom.Element;
import org.orbeon.dom.QName;
import org.orbeon.oxf.common.OrbeonLocationException;
import org.orbeon.oxf.util.IndentedLogger;
import org.orbeon.oxf.util.XPathCache;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.analysis.ElementAnalysis;
import org.orbeon.oxf.xforms.analysis.controls.ActionTrait;
import org.orbeon.oxf.xforms.event.Dispatch;
import org.orbeon.oxf.xforms.event.XFormsEvent;
import org.orbeon.oxf.xforms.event.XFormsEventTarget;
import org.orbeon.oxf.xforms.function.XFormsFunction;
import org.orbeon.oxf.xforms.xbl.Scope;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xml.NamespaceMapping;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.om.Item;
import org.orbeon.saxon.value.BooleanValue;
import org.orbeon.xforms.XFormsId;
import scala.Option;

import java.util.Collections;
import java.util.List;

/**
 * Execute a top-level XForms action and the included nested actions if any.
 */
public class XFormsActionInterpreter {

    private final IndentedLogger _indentedLogger;

    private final XBLContainer _container;
    private final XFormsContainingDocument _containingDocument;

    private final XFormsContextStack _actionXPathContext;

    public final Element outerActionElement;
    private final String handlerEffectiveId;

    public final XFormsEvent event;
    public final XFormsEventTarget eventObserver;

    public XFormsActionInterpreter(XBLContainer container, XFormsContextStack actionXPathContext, Element outerActionElement,
                                   String handlerEffectiveId, XFormsEvent event, XFormsEventTarget eventObserver) {

        this._container = container;
        this._containingDocument = container.getContainingDocument();

        this._indentedLogger = _containingDocument.getIndentedLogger(XFormsActions.LOGGING_CATEGORY());

        this._actionXPathContext = actionXPathContext;
        this.outerActionElement = outerActionElement;
        this.handlerEffectiveId = handlerEffectiveId;

        this.event = event;
        this.eventObserver = eventObserver;
    }

    public IndentedLogger indentedLogger() {
        return _indentedLogger;
    }

    public XBLContainer container() {
        return _container;
    }

    public XFormsContainingDocument containingDocument() {
        return _containingDocument;
    }

    public XFormsContextStack actionXPathContext() {
        return _actionXPathContext;
    }

    /**
     * Return the namespace mappings for the given action element.
     *
     * @param actionElement element to get namespace mapping for
     * @return              mapping
     */
    public NamespaceMapping getNamespaceMappings(Element actionElement) {
        return _container.getNamespaceMappings(actionElement);
    }

    /**
     * Execute an XForms action.
     */
    public void runAction(ElementAnalysis actionAnalysis) {

        final Element actionElement = actionAnalysis.element();
        final ActionTrait actionTrait = (ActionTrait) actionAnalysis;

        try {

            // Condition
            final String ifConditionAttribute      = actionTrait.ifConditionJava();
            final String whileIterationAttribute   = actionTrait.whileConditionJava();
            final String iterateIterationAttribute = actionTrait.iterateJava();

            // Push @iterate (if present) within the @model and @context context
            final NamespaceMapping namespaceMapping = actionAnalysis.namespaceMapping();
            // TODO: function context
            _actionXPathContext.pushBinding(
                iterateIterationAttribute,
                actionAnalysis.contextJava(),
                null,
                actionAnalysis.modelJava(),
                null,
                actionElement,
                namespaceMapping,
                getSourceEffectiveId(actionElement),
                actionAnalysis.scope(),
                false
            );

            // NOTE: At this point, the context has already been set to the current action element
            if (iterateIterationAttribute != null) {
                // Gotta iterate

                // NOTE: It's not 100% how @context and @iterate should interact here. Right now @iterate overrides @context,
                // i.e. @context is evaluated first, and @iterate sets a new context for each iteration
                {
                    final List<Item> currentNodeset = _actionXPathContext.getCurrentBindingContext().nodeset();
                    final int iterationCount = currentNodeset.size();
                    for (int index = 1; index <= iterationCount; index++) {

                        // Push iteration
                        _actionXPathContext.pushIteration(index);

                        final Item overriddenContextNodeInfo = currentNodeset.get(index - 1);
                        runSingleIteration(actionAnalysis, actionElement.getQName(),
                                ifConditionAttribute, whileIterationAttribute, true, overriddenContextNodeInfo);

                        // Restore context
                        _actionXPathContext.popBinding();
                    }
                }
            } else {
                // Do a single iteration run (but this may repeat over the @while condition!)
                runSingleIteration(actionAnalysis, actionElement.getQName(),
                        ifConditionAttribute, whileIterationAttribute, _actionXPathContext.getCurrentBindingContext().hasOverriddenContext(), _actionXPathContext.getCurrentBindingContext().contextItem());
            }

            // Restore
            _actionXPathContext.popBinding();
        } catch (Exception e) {
            throw OrbeonLocationException.wrapException(e, new ExtendedLocationData((LocationData) actionElement.getData(), "running XForms action", actionElement,
                    new String[]{"action name", actionElement.getQName().qualifiedName()}));
        }
    }

    private void runSingleIteration(ElementAnalysis actionAnalysis, QName actionQName,
                                    String ifConditionAttribute, String whileIterationAttribute, boolean hasOverriddenContext, Item contextItem) {

        // The context is now the overridden context
        int whileIteration = 1;
        while (true) {
            // Check if the conditionAttribute attribute exists and stop if false
            if (ifConditionAttribute != null) {
                boolean result = evaluateCondition(actionAnalysis.element(), actionQName.qualifiedName(), ifConditionAttribute, "if", contextItem);
                if (!result)
                    break;
            }
            // Check if the iterationAttribute attribute exists and stop if false
            if (whileIterationAttribute != null) {
                boolean result = evaluateCondition(actionAnalysis.element(), actionQName.qualifiedName(), whileIterationAttribute, "while", contextItem);
                if (!result)
                    break;
            }

            // We are executing the action
            if (_indentedLogger.isDebugEnabled()) {
                _indentedLogger.startHandleOperation("interpreter", "executing",
                    "action name", actionQName.qualifiedName(),
                    "while iteration", (whileIterationAttribute != null) ? Integer.toString(whileIteration) : null
                );
            }

            // Get action and execute it
            final DynamicActionContext dynamicActionContext =
                    new DynamicActionContext(this, actionAnalysis, hasOverriddenContext ? Option.apply(contextItem) : Option.apply(null));

            // Push binding excluding excluding @context and @model
            // NOTE: If we repeat, re-evaluate the action binding.
            // For example:
            //
            //   <xf:delete ref="/*/foo[1]" while="/*/foo"/>
            //
            // In this case, in the second iteration, xf:repeat must find an up-to-date nodeset!
            // TODO: function context
            _actionXPathContext.pushBinding(
                actionAnalysis.refJava(),
                null,
                null,
                null,
                actionAnalysis.bindJava(),
                actionAnalysis.element(),
                actionAnalysis.namespaceMapping(),
                getSourceEffectiveId(actionAnalysis.element()),
                actionAnalysis.scope(),
                false
            );

            XFormsActions.getAction(actionQName).execute(dynamicActionContext);

            _actionXPathContext.popBinding();

            if (_indentedLogger.isDebugEnabled()) {
                _indentedLogger.endHandleOperation(
                    "action name", actionQName.qualifiedName(),
                    "while iteration", (whileIterationAttribute != null) ? Integer.toString(whileIteration) : null);
            }

            // Stop if there is no iteration
            if (whileIterationAttribute == null)
                break;

            whileIteration++;
        }
    }

    private boolean evaluateCondition(Element actionElement,
                                      String actionName, String conditionAttribute, String conditionType,
                                      Item contextItem) {

        // Execute condition relative to the overridden context if it exists, or the in-scope context if not
        final List<Item> contextNodeset;
        final int contextPosition;
        {
            if (contextItem != null) {
                // Use provided context item
                contextNodeset = Collections.singletonList(contextItem);
                contextPosition = 1;
            } else {
                // Use empty context
                contextNodeset = XFormsConstants.EMPTY_ITEM_LIST;
                contextPosition = 0;
            }
        }

        // Don't evaluate the condition if the context has gone missing
        {
            if (contextNodeset.size() == 0) {//  || containingDocument.getInstanceForNode((NodeInfo) contextNodeset.get(contextPosition - 1)) == null
                if (_indentedLogger.isDebugEnabled())
                    _indentedLogger.logDebug("interpreter", "not executing", "action name", actionName, "condition type", conditionType, "reason", "missing context");
                return false;
            }
        }

        final List<Item> conditionResult = evaluateKeepItems(actionElement,
                contextNodeset, contextPosition, "boolean(" + conditionAttribute + ")");
        if (! ((BooleanValue) conditionResult.get(0)).effectiveBooleanValue()) {
            // Don't execute action

            if (_indentedLogger.isDebugEnabled())
                _indentedLogger.logDebug("interpreter", "not executing", "action name", actionName, "condition type", conditionType, "reason", "condition evaluated to 'false'", "condition", conditionAttribute);

            return false;
        } else {
            // Condition is true
            return true;
        }
    }

    /**
     * Return the source against which id resolutions are made for the given action element.
     *
     * @param   actionElement           action element to resolve
     * @return  effective id of source
     */
    public String getSourceEffectiveId(Element actionElement) {
        return XFormsId.getRelatedEffectiveId(handlerEffectiveId, getActionStaticId(actionElement));
    }

    /**
     * Evaluate an expression as a string. This returns "" if the result is an empty sequence.
     */
    public String evaluateAsString(Element actionElement, List<Item> nodeset, int position, String xpathExpression) {

        final XFormsFunction.Context functionContext = _actionXPathContext.getFunctionContext(getSourceEffectiveId(actionElement));

        // @ref points to something
        final String result = XPathCache.evaluateAsString(
                nodeset, position,
                xpathExpression, getNamespaceMappings(actionElement), _actionXPathContext.getCurrentBindingContext().getInScopeVariables(),
                _containingDocument.getFunctionLibrary(), functionContext, null,
                (LocationData) actionElement.getData(),
                containingDocument().getRequestStats().getReporter());

        return result != null ? result : "";
    }

    public List<Item> evaluateKeepItems(Element actionElement, List<Item> nodeset, int position, String xpathExpression) {

        final XFormsFunction.Context functionContext = _actionXPathContext.getFunctionContext(getSourceEffectiveId(actionElement));

        // @ref points to something
        return XPathCache.evaluateKeepItems(
                nodeset, position,
                xpathExpression, getNamespaceMappings(actionElement), _actionXPathContext.getCurrentBindingContext().getInScopeVariables(),
                _containingDocument.getFunctionLibrary(), functionContext, null,
                (LocationData) actionElement.getData(),
                containingDocument().getRequestStats().getReporter());
    }

    /**
     * Resolve a value which may be an AVT.
     *
     * @param actionElement     action element
     * @param attributeValue    raw value to resolve
     * @return                  resolved attribute value, null if the value is null or if the XPath context item is missing
     */
    public String resolveAVTProvideValue(Element actionElement, String attributeValue) {

        if (attributeValue == null)
            return null;

        // Whether this can't be an AVT
        final String resolvedAVTValue;
        if (XFormsUtils.maybeAVT(attributeValue)) {
            // We have to go through AVT evaluation
            final BindingContext bindingContext = _actionXPathContext.getCurrentBindingContext();

            // We don't have an evaluation context so return
            // CHECK: In the future we want to allow an empty evaluation context so do we really want this check?
            if (bindingContext.singleItemOpt().isEmpty())
                return null;

            final NamespaceMapping namespaceMapping = getNamespaceMappings(actionElement);
            final LocationData locationData = (LocationData) actionElement.getData();

            final XFormsFunction.Context functionContext = _actionXPathContext.getFunctionContext(getSourceEffectiveId(actionElement));

            resolvedAVTValue = XPathCache.evaluateAsAvt(
                bindingContext.nodeset(),
                bindingContext.position(),
                attributeValue,
                namespaceMapping,
                _actionXPathContext.getCurrentBindingContext().getInScopeVariables(),
                _containingDocument.getFunctionLibrary(),
                functionContext,
                null,
                locationData,
                containingDocument().getRequestStats().getReporter());
        } else {
            // We optimize as this doesn't need AVT evaluation
            resolvedAVTValue = attributeValue;
        }

        return resolvedAVTValue;
    }

    /**
     * Resolve the value of an attribute which may be an AVT.
     *
     * @param actionElement     action element
     * @param attributeName     QName of the attribute containing the value
     * @return                  resolved attribute value
     */
    public String resolveAVT(Element actionElement, QName attributeName) {
        return resolveAVTProvideValue(actionElement, actionElement.attributeValue(attributeName));
    }

    /**
     * Resolve the value of an attribute which may be an AVT.
     *
     * @param actionElement     action element
     * @param attributeName     name of the attribute containing the value
     * @return                  resolved attribute value
     */
    public String resolveAVT(Element actionElement, String attributeName) {
        // Get raw attribute value
        final String attributeValue = actionElement.attributeValue(attributeName);
        if (attributeValue == null)
            return null;

        return resolveAVTProvideValue(actionElement, attributeValue);
    }

    /**
     * Find an effective object based on either the xxf:repeat-indexes attribute, or on the current repeat indexes.
     *
     * @param actionElement             current action element
     * @param targetStaticOrAbsoluteId  static id or absolute id of the target to resolve
     * @return                          effective control if found
     */
    public XFormsObject resolveObject(Element actionElement, String targetStaticOrAbsoluteId) {

        // First resolve the object by static id
        final scala.Option<XFormsObject> result =
            _container.resolveObjectByIdInScope(getSourceEffectiveId(actionElement), targetStaticOrAbsoluteId, Option.apply(null));

        if (result.isEmpty()) {
            return null;
        } else {
            // Get indexes as space-separated list
            final String repeatIndexes = resolveAVT(actionElement, XFormsConstants.XXFORMS_REPEAT_INDEXES_QNAME);
            if (StringUtils.isBlank(repeatIndexes)) {
                // Most common case: just return the resolved object
                return result.get();
            } else {
                // Extension: repeat indexes are provided
                final String effectiveId = Dispatch.resolveRepeatIndexes(
                    _container,
                    result.get(),
                    getActionPrefixedId(actionElement),
                    repeatIndexes
                );
                return _containingDocument.getControlByEffectiveId(effectiveId);
            }
        }
    }

    public String getActionPrefixedId(Element actionElement) {
        return _container.getFullPrefix() + getActionStaticId(actionElement);
    }

    private String getActionStaticId(Element actionElement) {
        final String staticId = XFormsUtils.getElementId(actionElement);
        assert staticId != null;
        return staticId;
    }

    public Scope getActionScope(Element actionElement) {
        return _container.getPartAnalysis().scopeForPrefixedId(getActionPrefixedId(actionElement));
    }

    public boolean isDeferredUpdates(Element actionElement) {

        final BindingContext bindingContext = _actionXPathContext.getCurrentBindingContext();

        final boolean deferredUpdates;
        if (bindingContext.singleItemOpt().isDefined()) {
            deferredUpdates = ! "false".equals(resolveAVT(actionElement, XFormsConstants.XXFORMS_DEFERRED_UPDATES_QNAME));
        } else {
            // TODO: Presence of context is not the right way to decide whether to evaluate AVTs or not
            deferredUpdates = true;
        }
        return deferredUpdates;
    }
}
