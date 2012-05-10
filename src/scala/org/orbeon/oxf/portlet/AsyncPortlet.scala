/**
 * Copyright (C) 2012 Orbeon, Inc.
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
package org.orbeon.oxf.portlet

import actors.Future
import javax.portlet.{ResourceResponse, ResourceRequest, PortletRequest}
import actors.Futures._
import org.orbeon.oxf.externalcontext.WSRPURLRewriter
import org.orbeon.oxf.xforms.processor.{ResourcesAggregator, XFormsFeatures}
import org.orbeon.oxf.portlet.BufferedPortlet._
import collection.mutable.LinkedHashSet
import org.orbeon.oxf.pipeline.api.PipelineContext
import org.orbeon.oxf.util.URLRewriterUtils
import java.util.{List ⇒ JList}
import java.util.concurrent.Callable

// Support for asynchronous portlet content load
trait AsyncPortlet extends BufferedPortlet {

    def isMinimalResources: Boolean
    def isWSRPEncodeResources: Boolean

    private val FutureResponseSessionKey = "org.orbeon.oxf.future-response"
    private val IFrameContentResourcePath  = "/orbeon-iframe-content"

    type AsyncContext

    // Render the request in the background and immediately return a placeholder div
    // The context is a parameter to the render function which is guaranteed to be evaluated before the Future is scheduled
    def startAsyncRender(request: PortletRequest, context: AsyncContext, render: AsyncContext ⇒ ContentOrRedirect): ContentOrRedirect = {

        // Schedule a future to provide new content
        val parameterMap = toScalaMap(request.getParameterMap)
        val futureResponse = future {
            ResponseWithParameters(render(context), parameterMap)
        }
        setFutureResponse(request, futureResponse)

        // Output the placeholder HTML
        renderDiv(request)
    }

    // Serve the given resource
    // If the resource is a special request for the async portlet content, get it or wait for it.
    def asyncServeResource(request: ResourceRequest, response: ResourceResponse, serve: ⇒ Unit): Unit =
        if (request.getResourceID.startsWith(IFrameContentResourcePath + "?"))
            // Special case: serve content previously stored
            serveContentWaitIfNeeded(request, response)
        else
            // Serve normal resource
            serve

    // Serve stored content, or content from the future
    private def serveContentWaitIfNeeded(request: ResourceRequest, response: ResourceResponse): Unit =
        getResponseWithParameters(request) orElse getOrWaitFutureResponse(request) match {
            case Some(ResponseWithParameters(content: Content, parameters)) if toScalaMap(request.getParameterMap) == parameters ⇒
                // The result of an action with the current parameters is available OR is a result from a future
                response.setContentType("text/html")
                write(response, content.body, content.contentType)
            case _ ⇒
                throw new IllegalStateException("Processor execution did not return content.")
        }

    // Return <div> used to load content asynchronously
    private def renderDiv(request: PortletRequest): Content = {
        val pipelineContext = new PipelineContext
        try {
            // Create a request just so we can create a rewriter
            val ecRequest = new Portlet2ExternalContext(pipelineContext, null, request, false).getRequest
            // We know we want to rewrite all paths (no PFC will run here so we won't have anything better)
            val getMatchers = new Callable[JList[URLRewriterUtils.PathMatcher]] {
                def call = URLRewriterUtils.getMatchAllPathMatcher
            }
            val rewriter = new WSRPURLRewriter(getMatchers, ecRequest, isWSRPEncodeResources)

            // Output scripts needed by the Ajax portlet
            val sb = new StringBuilder
            sb append """<script type="text/javascript" src=""""

            def rewrite(path: String, encode: Boolean) =
                rewriter.rewriteResourceURL(path, encode)

            val resources = LinkedHashSet(XFormsFeatures.getAsyncPortletLoadScripts map (_.getResourcePath(isMinimalResources)): _*)
            ResourcesAggregator.aggregate(resources, path ⇒ sb append rewrite(path, isWSRPEncodeResources), isCacheCombinedResources = false, isCSS = false)

            sb append """"></script>"""

            // Output placeholder <div>
            // Append time so that the browser doesn't cache the resource
            val resourcePath = IFrameContentResourcePath + "?" + "orbeon-time=" + System.currentTimeMillis.toString
            sb append """<div class="orbeon-portlet-deferred" style="display: none">""" + rewrite(resourcePath, true) + """</div>"""

            Content(Left(sb.toString), Some("text/html"), None)

        } finally
            pipelineContext.destroy(true)
    }

    private def getOrWaitFutureResponse(request: PortletRequest) =
        getFutureResponse(request) map { futureResponse ⇒
            clearFutureResponse(request)
            futureResponse()
        }

    private def getFutureResponse(request: PortletRequest) =
        Option(request.getPortletSession.getAttribute(FutureResponseSessionKey).asInstanceOf[Future[ResponseWithParameters]])

    private def setFutureResponse(request: PortletRequest, responseWithParameters: Future[ResponseWithParameters]) =
        request.getPortletSession.setAttribute(FutureResponseSessionKey, responseWithParameters)

    private def clearFutureResponse(request: PortletRequest) =
        request.getPortletSession.removeAttribute(FutureResponseSessionKey)
}
