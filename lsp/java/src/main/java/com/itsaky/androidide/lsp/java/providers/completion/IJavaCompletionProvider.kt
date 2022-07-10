/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.lsp.java.providers.completion

import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.java.compiler.CompileTask
import com.itsaky.androidide.lsp.java.compiler.JavaCompilerService
import com.itsaky.androidide.lsp.java.edits.ClassImportEditHandler
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.CompletionData
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.CompletionItemKind.ENUM_MEMBER
import com.itsaky.androidide.lsp.models.CompletionItemKind.FUNCTION
import com.itsaky.androidide.lsp.models.CompletionItemKind.KEYWORD
import com.itsaky.androidide.lsp.models.CompletionItemKind.MODULE
import com.itsaky.androidide.lsp.models.CompletionItemKind.NONE
import com.itsaky.androidide.lsp.models.CompletionItemKind.PROPERTY
import com.itsaky.androidide.lsp.models.CompletionItemKind.VARIABLE
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.InsertTextFormat.SNIPPET
import com.itsaky.androidide.lsp.models.MatchLevel
import com.itsaky.androidide.utils.ILogger
import com.sun.source.tree.Tree
import com.sun.source.util.TreePath
import java.nio.file.Path
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind.ANNOTATION_TYPE
import javax.lang.model.element.ElementKind.CLASS
import javax.lang.model.element.ElementKind.CONSTRUCTOR
import javax.lang.model.element.ElementKind.ENUM
import javax.lang.model.element.ElementKind.ENUM_CONSTANT
import javax.lang.model.element.ElementKind.EXCEPTION_PARAMETER
import javax.lang.model.element.ElementKind.FIELD
import javax.lang.model.element.ElementKind.INSTANCE_INIT
import javax.lang.model.element.ElementKind.INTERFACE
import javax.lang.model.element.ElementKind.LOCAL_VARIABLE
import javax.lang.model.element.ElementKind.METHOD
import javax.lang.model.element.ElementKind.OTHER
import javax.lang.model.element.ElementKind.PACKAGE
import javax.lang.model.element.ElementKind.PARAMETER
import javax.lang.model.element.ElementKind.RESOURCE_VARIABLE
import javax.lang.model.element.ElementKind.STATIC_INIT
import javax.lang.model.element.ElementKind.TYPE_PARAMETER
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement

/**
 * Completion provider for Java source code.
 *
 * @author Akash Yadav
 */
abstract class IJavaCompletionProvider(
  protected val completingFile: Path,
  protected val cursor: Long,
  protected val compiler: JavaCompilerService,
  protected val settings: IServerSettings,
) {
  protected val log: ILogger = ILogger.newInstance(javaClass.name)
  protected lateinit var filePackage: String
  protected lateinit var fileImports: Set<String>

  open fun complete(
    task: CompileTask,
    path: TreePath,
    partial: String,
    endsWithParen: Boolean
  ): CompletionResult {
    val root = task.root(completingFile)
    filePackage = root.`package`.packageName.toString()
    fileImports = root.imports.map { it.qualifiedIdentifier.toString() }.toSet()
    return doComplete(task, path, partial, endsWithParen)
  }

  /**
   * Provide completions with the given data.
   *
   * @param task The compilation task. Subclasses are expected to use this compile task instead of
   * starting another compilation process.
   * @param path The [TreePath] defining the [Tree] at the current position.
   * @param partial The partial identifier.
   * @param endsWithParen `true` if the statement at cursor ends with a parenthesis. `false`
   * otherwise.
   */
  protected abstract fun doComplete(
    task: CompileTask,
    path: TreePath,
    partial: String,
    endsWithParen: Boolean,
  ): CompletionResult

  protected open fun matchLevel(candidate: CharSequence, partial: CharSequence): MatchLevel {
    return CompletionItem.matchLevel(candidate.toString(), partial.toString())
  }

  protected open fun putMethod(
    method: ExecutableElement,
    methods: MutableMap<String, MutableList<ExecutableElement>>,
  ) {
    val name = method.simpleName.toString()
    if (!methods.containsKey(name)) {
      methods[name] = ArrayList()
    }
    methods[name]!!.add(method)
  }

  protected open fun keyword(keyword: String, partial: String, matchRatio: Int): CompletionItem =
    keyword(keyword, partial, CompletionItem.matchLevel(keyword, partial))

  protected open fun keyword(
    keyword: String,
    partialName: CharSequence,
    matchLevel: MatchLevel,
  ): CompletionItem {
    val item = CompletionItem()
    item.setLabel(keyword)
    item.kind = KEYWORD
    item.detail = "keyword"
    item.sortText = keyword
    item.matchLevel = CompletionItem.matchLevel(keyword, partialName.toString())
    return item
  }

  protected open fun method(
    task: CompileTask,
    overloads: List<ExecutableElement>,
    addParens: Boolean,
    matchLevel: MatchLevel,
  ): CompletionItem {
    val first = overloads[0]
    val item = CompletionItem()
    item.setLabel(first.simpleName.toString())
    item.kind = CompletionItemKind.METHOD
    item.detail = first.returnType.toString() + " " + first
    item.sortText = item.label.toString()
    item.matchLevel = matchLevel
    val data = data(task, first, overloads.size)
    item.data = data
    if (addParens) {
      if (overloads.size == 1 && first.parameters.isEmpty()) {
        item.insertText = first.simpleName.toString() + "()$0"
      } else {
        item.insertText = first.simpleName.toString() + "($0)"
        item.command = Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS)
      }
      item.insertTextFormat = SNIPPET // Snippet
    }
    return item
  }

  protected open fun item(
    task: CompileTask,
    element: Element,
    matchLevel: MatchLevel
  ): CompletionItem {
    if (element.kind == METHOD) throw RuntimeException("method")
    val item = CompletionItem()
    item.setLabel(element.simpleName.toString())
    item.kind = kind(element)
    item.detail = element.toString()
    item.data = data(task, element, 1)
    item.sortText = item.label.toString()
    item.matchLevel = matchLevel
    return item
  }

  protected open fun classItem(className: String, matchLevel: MatchLevel): CompletionItem {
    return classItem(emptySet(), null, className, matchLevel)
  }

  protected open fun classItem(
    imports: Set<String>,
    file: Path?,
    className: String,
    matchLevel: MatchLevel,
  ): CompletionItem {
    val item = CompletionItem()
    item.setLabel(simpleName(className).toString())
    item.kind = CompletionItemKind.CLASS
    item.detail = className
    item.sortText = item.label.toString()
    item.matchLevel = matchLevel
    val data = CompletionData()
    data.className = className
    item.data = data

    // If file is not provided, we are probably completing an import path
    item.additionalEditHandler = if (file == null) null else ClassImportEditHandler(imports, file)
    return item
  }

  protected open fun simpleName(className: String): CharSequence {
    val dot = className.lastIndexOf('.')
    return if (dot == -1) className else className.subSequence(dot + 1, className.length)
  }

  protected open fun packageItem(name: String, matchLevel: MatchLevel): CompletionItem =
    CompletionItem().apply {
      setLabel(name)
      this.kind = MODULE
      this.sortText = name
      this.matchLevel = matchLevel
    }

  protected open fun kind(e: Element): CompletionItemKind {
    return when (e.kind) {
      ANNOTATION_TYPE -> CompletionItemKind.ANNOTATION_TYPE
      CLASS -> CompletionItemKind.CLASS
      CONSTRUCTOR -> CompletionItemKind.CONSTRUCTOR
      ENUM -> CompletionItemKind.ENUM
      ENUM_CONSTANT -> ENUM_MEMBER
      EXCEPTION_PARAMETER,
      PARAMETER, -> PROPERTY
      FIELD -> CompletionItemKind.FIELD
      STATIC_INIT,
      INSTANCE_INIT, -> FUNCTION
      INTERFACE -> CompletionItemKind.INTERFACE
      LOCAL_VARIABLE,
      RESOURCE_VARIABLE, -> VARIABLE
      METHOD -> CompletionItemKind.METHOD
      PACKAGE -> MODULE
      TYPE_PARAMETER -> CompletionItemKind.TYPE_PARAMETER
      OTHER -> NONE
      else -> NONE
    }
  }

  protected open fun data(task: CompileTask, element: Element, overloads: Int): CompletionData? {
    val data = CompletionData()
    when {
      element is TypeElement -> data.className = element.qualifiedName.toString()
      element.kind == FIELD -> {
        val field = element as VariableElement
        val type = field.enclosingElement as TypeElement
        data.className = type.qualifiedName.toString()
        data.memberName = field.simpleName.toString()
      }
      element is ExecutableElement -> {
        val types = task.task.types
        val type = element.enclosingElement as TypeElement
        data.className = type.qualifiedName.toString()
        data.memberName = element.simpleName.toString()
        data.erasedParameterTypes = Array(element.parameters.size) { "" }
        for (i in 0 until data.erasedParameterTypes.size) {
          val p = element.parameters[i].asType()
          data.erasedParameterTypes[i] = types.erasure(p).toString()
        }
        data.plusOverloads = overloads - 1
      }
      else -> {
        return null
      }
    }
    return data
  }
}
