// Copyright (c) 2012-2014 K Team. All Rights Reserved.
package org.kframework.compile.transformers;

import org.kframework.kil.ASTNode;
import org.kframework.kil.KApp;
import org.kframework.kil.KSorts;
import org.kframework.kil.ListTerminator;
import org.kframework.kil.Production;
import org.kframework.kil.ProductionItem;
import org.kframework.kil.Sort;
import org.kframework.kil.Sort2;
import org.kframework.kil.Term;
import org.kframework.kil.TermCons;
import org.kframework.kil.Token;
import org.kframework.kil.UserList;
import org.kframework.kil.loader.Constants;
import org.kframework.kil.loader.Context;
import org.kframework.kil.visitors.CopyOnWriteTransformer;
import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.KException.ExceptionType;
import org.kframework.utils.errorsystem.KException.KExceptionGroup;
import org.kframework.utils.general.GlobalSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Transformer class adding the implicit terminator (.List{"SEP"}) to user defined lists.
 */
public class AddEmptyLists extends CopyOnWriteTransformer {

    public AddEmptyLists(Context context) {
        super("Add empty lists", context);
    }

    @Override
    public ASTNode visit(TermCons tc, Void _) {
        // traverse
        Production p = tc.getProduction();

        if (p.isListDecl()) {
            Term t = tc.getContents().get(0);
            UserList ul = (UserList) p.getItems().get(0);
            if (isAddEmptyList(ul.getSort(), t.getSort())) {
                if (!isUserListElement(ul.getSort(), t, context)) {
                    String msg = "Found sort '" + t.getSort() + "' where list sort '" + ul.getSort() + "' was expected. Moving on.";
                    GlobalSettings.kem.register(new KException(ExceptionType.HIDDENWARNING, KExceptionGroup.LISTS, msg, t.getFilename(), t.getLocation()));
                } else
                    tc.getContents().set(0, addEmpty(t, ul.getSort()));
            }

            // if the term should be a list, append the empty element
            t = tc.getContents().get(1);
            if (isAddEmptyList(p.getSort(), t.getSort())) {
                if (!isUserListElement(p.getSort(), t, context)) {
                    String msg = "Found sort '" + t.getSort() + "' where list sort '" + p.getSort() + "' was expected. Moving on.";
                    GlobalSettings.kem.register(new KException(ExceptionType.HIDDENWARNING, KExceptionGroup.LISTS, msg, t.getFilename(), t.getLocation()));
                } else
                    tc.getContents().set(1, addEmpty(t, tc.getProduction().getSort()));
            }
        } else {
            for (int i = 0, j = 0; j < p.getItems().size(); j++) {
                ProductionItem pi = p.getItems().get(j);
                if (!(pi instanceof Sort))
                    continue;

                Sort2 srt = Sort2.of(((Sort) pi).getName());
                if (context.isListSort(srt.getName())) {
                    Term t = tc.getContents().get(i);
                    // if the term should be a list, append the empty element
                    if (isAddEmptyList(srt, t.getSort())) {
                        if (!isUserListElement(srt, t, context)) {
                            String msg = "Found sort '" + t.getSort() + "' where list sort '" + srt + "' was expected. Moving on.";
                            GlobalSettings.kem.register(new KException(ExceptionType.HIDDENWARNING, KExceptionGroup.LISTS, msg, t.getFilename(), t.getLocation()));
                        } else
                            tc.getContents().set(i, addEmpty(t, srt));
                    }
                }
                i++;
            }
        }

        return super.visit(tc, _);
    }

    private boolean isUserListElement(Sort2 listSort, Term element, Context context) {
        Sort2 elementSort = element.getSort();

        /* TODO: properly infer sort of KApp */
        if (elementSort.equals(Sort2.KITEM) && element instanceof KApp) {
            /* infer sort for builtins and tokens */
            if (((KApp) element).getLabel() instanceof Token) {
                elementSort = ((Token) ((KApp) element).getLabel()).tokenSort();
            }
        }

        return !elementSort.equals(Sort2.KITEM)
               && context.isSubsortedEq(listSort.getName(), elementSort.getName());
    }

    public boolean isAddEmptyList(Sort2 expectedSort, Sort2 termSort) {
        if (!context.isListSort(expectedSort.getName()))
            return false;
        if (context.isSubsortedEq(expectedSort.getName(), termSort.getName())
                && context.isListSort(termSort.getName()))
            return false;
        return true;
    }

    private Term addEmpty(Term node, Sort2 sort) {
        TermCons tc = new TermCons(sort, getListCons(sort.getName()), context);
        List<Term> genContents = new ArrayList<Term>();
        genContents.add(node);
        genContents.add(new ListTerminator(sort, null));

        tc.setContents(genContents);
        return tc;
    }

    private String getListCons(String psort) {
        Production p = context.listConses.get(psort);
        return p.getAttribute(Constants.CONS_cons_ATTR);
    }
}
