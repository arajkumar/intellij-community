package com.jetbrains.python.codeInsight.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.documentation.PyDocumentationSettings;
import com.jetbrains.python.psi.PyDocStringOwner;
import com.jetbrains.python.psi.PyStringLiteralExpression;
import com.jetbrains.python.refactoring.PyRefactoringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

import static com.intellij.patterns.PlatformPatterns.psiElement;

/**
 * User : ktisha
 */
public class PyDocstringCompletionContributor extends CompletionContributor {
  public PyDocstringCompletionContributor() {
    extend(CompletionType.BASIC,
           psiElement().inside(PyStringLiteralExpression.class).withElementType(PyTokenTypes.DOCSTRING),
           new IdentifierCompletionProvider());
  }

  private static class IdentifierCompletionProvider extends CompletionProvider<CompletionParameters> {

    private IdentifierCompletionProvider() {
    }

    @Override
    protected void addCompletions(@NotNull CompletionParameters parameters,
                                  ProcessingContext context,
                                  @NotNull CompletionResultSet result) {
      if (parameters.getInvocationCount() == 0) return;
      final PyDocStringOwner docStringOwner = PsiTreeUtil.getParentOfType(parameters.getOriginalPosition(), PyDocStringOwner.class);
      if (docStringOwner != null) {
        final PsiFile file = docStringOwner.getContainingFile();
        final Module module = ModuleUtilCore.findModuleForPsiElement(docStringOwner);
        if (module != null) {
          final PyDocumentationSettings settings = PyDocumentationSettings.getInstance(module);
          if (!settings.isPlain(file)) return;
          result = result.withPrefixMatcher(getPrefix(parameters.getOffset(), file));
          final Collection<String> identifiers = PyRefactoringUtil.collectUsedNames(docStringOwner);
          for (String identifier : identifiers)
            result.addElement(LookupElementBuilder.create(identifier));


          final Collection<String> fileIdentifiers = PyRefactoringUtil.collectUsedNames(parameters.getOriginalFile());
          for (String identifier : fileIdentifiers)
            result.addElement(LookupElementBuilder.create(identifier));
        }
      }
    }
  }

  private static String getPrefix(int offset, PsiFile file) {
    if (offset > 0) {
      offset--;
    }
    final String text = file.getText();
    StringBuilder prefixBuilder = new StringBuilder();
    while(offset > 0 && Character.isLetterOrDigit(text.charAt(offset))) {
      prefixBuilder.insert(0, text.charAt(offset));
      offset--;
    }
    return prefixBuilder.toString();
  }

  @Override
  public boolean invokeAutoPopup(@NotNull PsiElement position, char typeChar) {
    return false;
  }
}
