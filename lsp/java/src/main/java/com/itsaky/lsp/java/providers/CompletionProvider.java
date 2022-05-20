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

package com.itsaky.lsp.java.providers;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ReflectUtils;
import com.itsaky.androidide.utils.ILogger;
import com.itsaky.lsp.api.AbstractServiceProvider;
import com.itsaky.lsp.api.ICompletionProvider;
import com.itsaky.lsp.api.IServerSettings;
import com.itsaky.lsp.java.compiler.CompilerProvider;
import com.itsaky.lsp.java.compiler.SourceFileObject;
import com.itsaky.lsp.java.compiler.SynchronizedTask;
import com.itsaky.lsp.java.parser.ParseTask;
import com.itsaky.lsp.java.providers.completion.IJavaCompletionProvider;
import com.itsaky.lsp.java.providers.completion.IdentifierCompletionProvider;
import com.itsaky.lsp.java.providers.completion.ImportCompletionProvider;
import com.itsaky.lsp.java.providers.completion.KeywordCompletionProvider;
import com.itsaky.lsp.java.providers.completion.MemberReferenceCompletionProvider;
import com.itsaky.lsp.java.providers.completion.MemberSelectCompletionProvider;
import com.itsaky.lsp.java.providers.completion.SwitchConstantCompletionProvider;
import com.itsaky.lsp.java.providers.completion.TopLevelSnippetsProvider;
import com.itsaky.lsp.java.visitors.FindCompletionsAt;
import com.itsaky.lsp.java.visitors.PruneMethodBodies;
import com.itsaky.lsp.models.CompletionParams;
import com.itsaky.lsp.models.CompletionResult;
import com.sun.source.util.TreePath;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CompletionProvider extends AbstractServiceProvider implements ICompletionProvider {

  public static final int MAX_COMPLETION_ITEMS = CompletionResult.MAX_ITEMS;
  private static final ILogger LOG = ILogger.newInstance("JavaCompletionProvider");
  private final CompilerProvider compiler;

  public CompletionProvider(CompilerProvider compiler, IServerSettings settings) {
    super();
    super.applySettings(settings);

    this.compiler = compiler;
  }

  @Override
  public boolean canComplete(Path file) {
    return ICompletionProvider.super.canComplete(file) && file.toFile().getName().endsWith(".java");
  }

  @NonNull
  @Override
  public CompletionResult complete(@NonNull CompletionParams params) {
    return complete(
        params.getFile(), params.getPosition().getLine(), params.getPosition().getColumn());
  }

  public CompletionResult complete(@NonNull Path file, int line, int column) {
    LOG.info("Complete at " + file.getFileName() + "(" + line + "," + column + ")...");

    // javac expects 1-based line and column indexes
    line++;
    column++;

    Instant started = Instant.now();
    ParseTask task = compiler.parse(file);

    long cursor = task.root.getLineMap().getPosition(line, column);
    StringBuilder contents = new PruneMethodBodies(task.task).scan(task.root, cursor);
    int endOfLine = endOfLine(contents, (int) cursor);
    contents.insert(endOfLine, ';');

    CompletionResult result = compileAndComplete(file, contents.toString(), cursor);
    if (result == null) {
      result = CompletionResult.EMPTY;
    }

    new TopLevelSnippetsProvider().complete(task, result);
    logCompletionTiming(started, result.getItems(), result.isIncomplete());
    return result;
  }

  private int endOfLine(@NonNull CharSequence contents, int cursor) {
    while (cursor < contents.length()) {
      char c = contents.charAt(cursor);
      if (c == '\r' || c == '\n') break;
      cursor++;
    }
    return cursor;
  }

  private CompletionResult compileAndComplete(Path file, String contents, long cursor) {
    Instant started = Instant.now();
    SourceFileObject source = new SourceFileObject(file, contents, Instant.now());
    String partial = partialIdentifier(contents, (int) cursor);
    boolean endsWithParen = endsWithParen(contents, (int) cursor);
    SynchronizedTask synchronizedTask = compiler.compile(Collections.singletonList(source));
    return synchronizedTask.get(
        task -> {
          LOG.info("...compiled in " + Duration.between(started, Instant.now()).toMillis() + "ms");
          TreePath path = new FindCompletionsAt(task.task).scan(task.root(), cursor);

          final Class<? extends IJavaCompletionProvider> klass;
          switch (path.getLeaf().getKind()) {
            case IDENTIFIER:
              klass = IdentifierCompletionProvider.class;
              break;
            case MEMBER_SELECT:
              klass = MemberSelectCompletionProvider.class;
              break;
            case MEMBER_REFERENCE:
              klass = MemberReferenceCompletionProvider.class;
              break;
            case SWITCH:
              klass = SwitchConstantCompletionProvider.class;
              break;
            case IMPORT:
              klass = ImportCompletionProvider.class;
              break;
            default:
              klass = KeywordCompletionProvider.class;
              break;
          }

          final IJavaCompletionProvider provider =
              ReflectUtils.reflect(klass).newInstance(file, cursor, compiler, getSettings()).get();

          if (provider instanceof ImportCompletionProvider) {
            ((ImportCompletionProvider) provider)
                .setImportPath(qualifiedPartialIdentifier(contents, (int) cursor));
          }
          
          return provider.complete (task, path, partial, endsWithParen);
        });
  }

  @NonNull
  private String partialIdentifier(String contents, int end) {
    int start = end;
    while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
      start--;
    }
    return contents.substring(start, end);
  }

  private boolean endsWithParen(@NonNull String contents, int cursor) {
    for (int i = cursor; i < contents.length(); i++) {
      if (!Character.isJavaIdentifierPart(contents.charAt(i))) {
        return contents.charAt(i) == '(';
      }
    }
    return false;
  }

  @NonNull
  private String qualifiedPartialIdentifier(String contents, int end) {
    int start = end;
    while (start > 0 && isQualifiedIdentifierChar(contents.charAt(start - 1))) {
      start--;
    }
    return contents.substring(start, end);
  }

  private boolean isQualifiedIdentifierChar(char c) {
    return c == '.' || Character.isJavaIdentifierPart(c);
  }
  
  private void logCompletionTiming(Instant started, List<?> list, boolean isIncomplete) {
    long elapsedMs = Duration.between(started, Instant.now()).toMillis();
    if (isIncomplete) {
      LOG.info(
              String.format(
                      Locale.getDefault(),
                      "Found %d items (incomplete) in %,d ms",
                      list.size(),
                      elapsedMs));
    } else {
      LOG.info(
              String.format(
                      Locale.getDefault(), "...found %d items in %,d ms", list.size(), elapsedMs));
    }
  }
}
