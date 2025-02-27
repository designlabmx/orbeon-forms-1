/**
  * Copyright (C) 2019 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.xforms

import io.circe.generic.auto._
import io.circe.parser.decode
import org.orbeon.facades.{Bowser, Mousetrap}
import org.orbeon.liferay._
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.xforms.Constants._
import org.orbeon.xforms.EventNames.{DOMContentLoaded, KeyModifiersPropertyName, KeyTextPropertyName}
import org.orbeon.xforms.StateHandling.StateResult
import org.orbeon.xforms.facade._
import org.orbeon.xforms.rpc.Initializations
import org.scalajs.dom
import org.scalajs.dom.ext._
import org.scalajs.dom.html

import scala.collection.{mutable ⇒ m}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.Dictionary
import scala.scalajs.js.Dynamic.{global ⇒ g}
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

@JSExportTopLevel("ORBEON.xforms.InitSupport")
object InitSupport {

  import Private._

  def pageContainsFormsMarkup(): Unit =
    pageContainsFormsMarkupPromise.success(())

  // TODO: `opsXFormsProperties`, `xformsPageLoadedServer`.
  // Called by form-specific dynamic initialization
  @JSExport
  def initializeFormWithInitData(initializationsString: String): Unit = {

    val initializations =
      decode[rpc.Initializations](initializationsString) match {
        case Left(e)  ⇒ throw e
        case Right(i) ⇒ i
      }

    scribe.debug(s"initialization data is ready for form `${initializations.namespacedFormId}`/`${initializations.uuid}`")

    pageContainsFormsMarkupF foreach { _ ⇒

      scribe.debug(s"initializing form `${initializations.namespacedFormId}`/`${initializations.uuid}`")

      initializeForm(initializations)
      initializeHeartBeatIfNeeded()

      if (Page.countInitializedForms == allFormElems.size) {
        scribe.debug(s"all forms are loaded")
        scheduleOrbeonLoadedEventIfNeeded()
      }
    }
  }

  def atLeastDomInteractiveF: Future[Unit] = {

    scribe.debug(s"document state is `${dom.document.readyState}`")

    val promise = Promise[Unit]()

    if (dom.document.readyState == EventNames.InteractiveReadyState || dom.document.readyState == EventNames.CompleteReadyState) {

      // Because yes, the document is interactive, but JavaScript placed after us might not have run yet.
      // Although if we do everything in an async way, that should be changed.
      // TODO: Review once full order of JavaScript is determined in `App` doc.
      js.timers.setTimeout(0) {
        promise.success(())
      }
    } else {

      lazy val contentLoaded: js.Function1[dom.Event, _] = (_: dom.Event) ⇒ {
        scribe.debug(s"$DOMContentLoaded handler called")
        dom.document.removeEventListener(DOMContentLoaded, contentLoaded)
        promise.success(())
      }

      dom.document.addEventListener(DOMContentLoaded, contentLoaded)
    }

    promise.future
  }

  def liferayF: Future[Unit] = {
    scribe.debug("checking for Liferay object")
    dom.window.Liferay.toOption match {
      case None          ⇒ Future.successful(())
      case Some(liferay) ⇒ liferay.allPortletsReadyF
    }
  }

  def setupGlobalClassesIfNeeded(): Unit = {

    val jBody = $(dom.document.body)

    // For embedding as we don't have control over the generation of the `<body>` element
    // Remove once we no longer depend on YUI widgets at all anymore.
    jBody.addClass(Constants.YuiSkinSamClass)

    // TODO: With embedding, consider placing those on the root element of the embedded code. Watch for dialogs behavior.
    if (Bowser.ios.contains(true))
        jBody.addClass(Constants.XFormsIosClass)

    if (Bowser.mobile.contains(true))
        jBody.addClass(Constants.XFormsMobileClass)
  }

  @JSExport
  def initializeJavaScriptControlsFromSerialized(initData: String): Unit =
    decode[List[rpc.Control]](initData) match {
      case Left(_)  ⇒
        // TODO: error
        None
      case Right(controls) ⇒
        initializeJavaScriptControls(controls)
    }

  @JSExport
  def processRepeatHierarchyUpdateForm(formId: String, repeatTreeString: String): Unit = {

    val (repeatTreeChildToParent, repeatTreeParentToAllChildren) =
      processRepeatHierarchy(repeatTreeString)

    val form = Page.getForm(formId)

    form.repeatTreeChildToParent       = repeatTreeChildToParent
    form.repeatTreeParentToAllChildren = repeatTreeParentToAllChildren
  }

  private object Private {

    val pageContainsFormsMarkupPromise = Promise[Unit]()

    private var heartBeatInitialized       = false
    private var orbeonLoadedEventScheduled = false

    def initializeForm(initializations: Initializations): Unit = {

      val formId   = initializations.namespacedFormId
      val formElem = dom.document.getElementById(formId).asInstanceOf[html.Form] // TODO: Error instead of plain cast?

      // The error panel shouldn't depend on much and is useful early on
      val errorPanel = ErrorPanel.initializeErrorPanel(formElem) getOrElse
        (throw new IllegalStateException(s"missing error panel element for form `$formId`"))

      // Q: Do this later?
      $(formElem).removeClass(Constants.InitiallyHiddenClass)

      val uuid =
        StateHandling.initializeState(formId, initializations.uuid) match {
          case StateResult.Uuid(uuid) ⇒
            uuid
          case StateResult.Restore(uuid) ⇒
            AjaxServer.fireEvents(
              events      = js.Array(new AjaxServer.Event(formElem, null, null, EventNames.XXFormsAllEventsRequired)),
              incremental = false
            )
            uuid
          case StateResult.Reload ⇒
            dom.window.location.reload(flag = true)
            return
        }

      val uuidInput        = getTwoPassSubmissionField(formElem, UuidFieldName)
      val serverEventInput = getTwoPassSubmissionField(formElem, ServerEventsFieldName)

      val (repeatTreeChildToParent, repeatTreeParentToAllChildren) =
        processRepeatHierarchy(initializations.repeatTree)

      // NOTE on paths: We switched back and forth between trusting the client or the server. Starting 2010-08-27
      // the server provides the info. Starting 2011-10-05 we revert to using the server values instead of client
      // detection, as that works in portals. The concern with using the server values was proxying. But should
      // proxying be able to change the path itself? If so, wouldn't other things break anyway? So for now
      // server values it is.

      Page.setForm(
        formId,
        new Form(
          uuid                          = uuid,
          elem                          = formElem,
          uuidInput                     = uuidInput,
          serverEventInput              = serverEventInput,
          ns                            = formId.substring(0, formId.indexOf(Constants.FormClass)),
          xformsServerPath              = initializations.xformsServerPath,
          xformsServerUploadPath        = initializations.xformsServerUploadPath,
          calendarImagePath             = initializations.calendarImagePath,
          errorPanel                    = errorPanel,
          repeatTreeChildToParent       = repeatTreeChildToParent,
          repeatTreeParentToAllChildren = repeatTreeParentToAllChildren,
          repeatIndexes                 = processRepeatIndexes(initializations.repeatIndexes)
        )
      )

      // TODO: We set those here, but we could set them just before submission instead.
      uuidInput.value        = uuid
      serverEventInput.value = ""

      initializeJavaScriptControls(initializations.controls)
      initializeKeyListeners(initializations.listeners, formElem)

      dispatchInitialServerEvents(initializations.events, formId)

      // Special registration for `focus`, `blur`, and `change` events
      $(dom.document).on("focusin",  Events.focus)
      $(dom.document).on("focusout", Events.blur)
      $(dom.document).on("change",   Events.change)

      // Register events that bubble on document for all browsers
      // TODO: Move away from YUI even listeners.
      if (! Globals.topLevelListenerRegistered) {
        g.YAHOO.util.Event.addListener(dom.document, "keypress",  Events.keypress)
        g.YAHOO.util.Event.addListener(dom.document, "keydown",   Events.keydown)
        g.YAHOO.util.Event.addListener(dom.document, "keyup",     Events.keyup)
        g.YAHOO.util.Event.addListener(dom.document, "mouseover", Events.mouseover)
        g.YAHOO.util.Event.addListener(dom.document, "mouseout",  Events.mouseout)
        g.YAHOO.util.Event.addListener(dom.document, "click",     Events.click)
        g.YAHOO.widget.Overlay.windowScrollEvent.subscribe(Events.scrollOrResize)
        g.YAHOO.widget.Overlay.windowResizeEvent.subscribe(Events.scrollOrResize)

        Globals.topLevelListenerRegistered = true
      }

      // Putting this here due to possible Scala.js bug reporting a "applyDynamic does not support passing a vararg parameter"
      val hasOtherScripts = ! js.isUndefined(g.xformsPageLoadedServer)

      // Run user scripts
      initializations.userScripts foreach { case rpc.UserScript(functionName, targetId, observerId, paramsValues) ⇒
        ServerAPI.callUserScript(formId, functionName, targetId, observerId, paramsValues map (_.asInstanceOf[js.Any]): _*)
      }

      // Run other code sent by server
      // TODO: `showMessages`, `showDialog`, `setFocus`, `showError` must be part of `Initializations`.
      // TODO: Handle `javascript:` loads as well.
      // TODO: `xformsPageLoadedServer` must be per form too! Currently, it is global.
      if (hasOtherScripts)
        g.xformsPageLoadedServer()
    }

        // The heartbeat is per servlet session and we only need one. But see https://github.com/orbeon/orbeon-forms/issues/2014.
    def initializeHeartBeatIfNeeded(): Unit =
      if (! heartBeatInitialized) {
        if (Properties.sessionHeartbeat.get()) {
          // Say session is 60 minutes: heartbeat must come after 48 minutes and we check every 4.8 minutes
          val heartBeatDelay      = Properties.sessionHeartbeatDelay.get()
          val heartBeatCheckDelay = heartBeatDelay / 10
          if (heartBeatCheckDelay > 0) {
            scribe.debug(s"setting heartbeat check every $heartBeatCheckDelay ms")
            js.timers.setInterval(heartBeatCheckDelay) {
              Events.sendHeartBeatIfNeeded(heartBeatDelay)
            }
          }
        }
        heartBeatInitialized = true
      }

    def scheduleOrbeonLoadedEventIfNeeded(): Unit =
      if (! orbeonLoadedEventScheduled) {
        // See https://doc.orbeon.com/xforms/core/client-side-javascript-api#custom-events
        // See https://github.com/orbeon/orbeon-forms/issues/3729
        // 2019-01-10: There was an old comment about how the call to `this.subscribers.length` in the `fire()`
        // method could hang with IE. That is likely no longer a relevant comment but it might still be
        // better to fire the event asynchronously, although we could maybe use a `0` delay.
        js.timers.setTimeout(Properties.internalShortDelay.get()) {
          scribe.debug("dispatching `orbeonLoadedEvent`")
          Events.orbeonLoadedEvent.fire()
        }
        orbeonLoadedEventScheduled = true
      }

    def pageContainsFormsMarkupF: Future[Unit] =
      pageContainsFormsMarkupPromise.future

    def allFormElems: Seq[html.Form] =
      dom.document.forms filter (_.classList.contains(Constants.FormClass)) collect { case f: html.Form ⇒ f }

    def getTwoPassSubmissionField(formElem: html.Form, fieldName: String): html.Input =
      formElem.elements.iterator                          collectFirst
        { case e: html.Input if fieldName == e.name ⇒ e } getOrElse
        (throw new IllegalStateException(s"missing hidden field for form `$fieldName`"))

    def parseRepeatIndexes(repeatIndexesString: String): List[(String, String)] =
      for {
        repeatIndexes ← repeatIndexesString.splitTo[List](",")
        repeatInfos   = repeatIndexes.splitTo[List]() // must be of the form "a b"
      } yield
        repeatInfos.head → repeatInfos.last

    def parseRepeatTree(repeatTreeString: String): List[(String, String)] =
      for {
       repeatTree  ← repeatTreeString.splitTo[List](",")
       repeatInfos = repeatTree.splitTo[List]() // must be of the form "a b"
       if repeatInfos.size > 1
     } yield
       repeatInfos.head → repeatInfos.last

    def createParentToChildrenMap(childToParentMap: Map[String, String]): collection.Map[String, js.Array[String]] = {

      val parentToChildren = m.Map[String, js.Array[String]]()

       childToParentMap foreach { case (child, parent) ⇒
         Iterator.iterateOpt(parent)(childToParentMap.get) foreach { p ⇒
           parentToChildren.getOrElseUpdate(p, js.Array[String]()).push(child)
         }
       }

      parentToChildren
    }

    def processRepeatHierarchy(repeatTreeString: String): (Dictionary[String], Dictionary[js.Array[String]]) = {

      val childToParent    = parseRepeatTree(repeatTreeString)
      val childToParentMap = childToParent.toMap

      val parentToChildren = m.Map[String, js.Array[String]]()

      childToParentMap foreach { case (child, parent) ⇒
        Iterator.iterateOpt(parent)(childToParentMap.get) foreach { p ⇒
          parentToChildren.getOrElseUpdate(p, js.Array[String]()).push(child)
        }
      }

      (childToParentMap.toJSDictionary, createParentToChildrenMap(childToParentMap).toJSDictionary)
    }

    def processRepeatIndexes(repeatIndexesString: String): Dictionary[String] =
      parseRepeatIndexes(repeatIndexesString).toMap.toJSDictionary

    def initializeJavaScriptControls(controls: List[rpc.Control]): Unit =
      controls foreach { case rpc.Control(id, valueOpt) ⇒
        Option(dom.document.getElementById(id).asInstanceOf[html.Element]) foreach { control ⇒
          val jControl = $(control)
          // Exclude controls in repeat templates
          if (jControl.parents(".xforms-repeat-template").length == 0) {
            if (XBL.isComponent(control)) {
              // Custom XBL component initialization
              for {
                _     ← Option(XBL.instanceForControl(control))
                value ← valueOpt
              } locally {
                Controls.setCurrentValue(control, value)
              }
            } else if (jControl.is(".xforms-dialog.xforms-dialog-visible-true")) {
                // Initialized visible dialogs
                Init._dialog(control)
            } else if (jControl.is(".xforms-select1-appearance-compact, .xforms-select-appearance-compact")) {
                // Legacy JavaScript initialization
                Init._compactSelect(control)
            } else if (jControl.is(".xforms-range")) {
                // Legacy JavaScript initialization
                Init._range(control)
            }
          }
        }
      }

    def initializeKeyListeners(listeners: List[rpc.KeyListener], formElem: html.Form): Unit =
      listeners foreach { case rpc.KeyListener(eventNames, observer, keyText, modifiers) ⇒

        // NOTE: 2019-01-07: We don't handle dialogs yet.
        //if (dom.document.getElementById(observer).classList.contains("xforms-dialog"))

        val mousetrap =
          if (observer == Constants.DocumentId)
            Mousetrap
          else
            Mousetrap(dom.document.getElementById(observer).asInstanceOf[html.Element])

        val modifierStrings =
          modifiers.toList map (_.entryName)

        val modifierString =
          modifierStrings mkString " "

        val callback: js.Function = (e: dom.KeyboardEvent, combo: String) ⇒ {

          val properties =
            Map(KeyTextPropertyName → keyText) ++
              (modifiers map (_ ⇒ KeyModifiersPropertyName → modifierString))

          DocumentAPI.dispatchEvent(
            targetId    = observer,
            eventName   = e.`type`,
            incremental = false,
            properties  = properties.toJSDictionary
          )

          if (modifiers.nonEmpty)
            e.preventDefault()
        }

        val keys = modifierStrings ::: List(keyText.toLowerCase) mkString "+"

        // It is unlikely that supporting multiple event names is very useful, but you can imagine
        // in theory supporting both `keydown` and `keyup` for example.
        eventNames foreach { eventName ⇒
          mousetrap.bind(keys, callback, eventName)
        }
      }

    def dispatchInitialServerEvents(events: List[rpc.ServerEvent], formId: String): Unit =
      events foreach { case rpc.ServerEvent(delay, discardable, showProgress, event) ⇒
        AjaxServer.createDelayedServerEvent(
          serverEvents = event,
          delay        = delay.toDouble,
          showProgress = showProgress,
          discardable  = discardable,
          formId       = formId
        )
      }
  }
}
