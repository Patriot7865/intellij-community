// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.codeInsight.navigation.impl

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.*
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.codeInsight.navigation.impl.NavigationActionResult.SingleTarget
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.DumbModeAccessType
import org.jetbrains.annotations.ApiStatus.Internal
import kotlin.coroutines.cancellation.CancellationException

@Internal
fun fromGTDProviders(project: Project, editor: Editor, offset: Int): GTDActionData? {
  return processInjectionThenHost(editor, offset) { _editor, _offset ->
    fromGTDProvidersInner(project, _editor, _offset)
  }
}

/**
 * @see com.intellij.codeInsight.navigation.actions.GotoDeclarationAction.findTargetElementsFromProviders
 */
private fun fromGTDProvidersInner(project: Project, editor: Editor, offset: Int): GTDActionData? {
  val document = editor.document
  val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
  val adjustedOffset: Int = TargetElementUtil.adjustOffset(file, document, offset)
  val leafElement: PsiElement = file.findElementAt(adjustedOffset) ?: return null

  return DumbModeAccessType.RELIABLE_DATA_ONLY.ignoreDumbMode(ThrowableComputable {
    for (handler in GotoDeclarationHandler.EP_NAME.extensionList) {
      val fromProvider: Array<out PsiElement>? = try {
        handler.getGotoDeclarationTargets(leafElement, offset, editor)
      }
      catch (ce: CancellationException) {
        throw ce
      }
      catch (inre: IndexNotReadyException) {
        throw inre // clients should catch and either show dumb mode notification or ignore
      }
      catch (t: Throwable) {
        LOG.error(t)
        null
      }
      if (fromProvider.isNullOrEmpty()) {
        continue
      }
      return@ThrowableComputable GTDProviderData(leafElement, fromProvider.toList(), handler)
    }
    return@ThrowableComputable null
  })
}

private class GTDProviderData(
  private val leafElement: PsiElement,
  private val targetElements: Collection<PsiElement>,
  private val navigationProvider: GotoDeclarationHandler
) : GTDActionData {

  init {
    require(targetElements.isNotEmpty())
  }

  override fun ctrlMouseData(): CtrlMouseData {
    val singleTarget = targetElements.singleOrNull()
    if (singleTarget == null) {
      return multipleTargetsCtrlMouseData(getReferenceRanges(leafElement))
    }
    else {
      return psiCtrlMouseData(leafElement, singleTarget)
    }
  }

  override fun result(): NavigationActionResult? {
    return when (targetElements.size) {
      0 -> null
      1 -> {
        targetElements.single().gtdTargetNavigatable()?.navigationRequest()?.let { request ->
          SingleTarget({ request }, navigationProvider)
        }
      }
      else -> {
        val targets = targetElements.map { targetElement ->
          LazyTargetWithPresentation(
            { targetElement.psiNavigatable()?.navigationRequest() },
            targetPresentation(targetElement),
            navigationProvider
          )
        }
        NavigationActionResult.MultipleTargets(targets)
      }
    }
  }
}
