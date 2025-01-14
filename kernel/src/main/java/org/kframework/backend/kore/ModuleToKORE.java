// Copyright (c) 2018-2019 K Team. All Rights Reserved.
package org.kframework.backend.kore;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import org.kframework.Collections;
import org.kframework.attributes.Att;
import org.kframework.builtin.BooleanUtils;
import org.kframework.builtin.Hooks;
import org.kframework.builtin.KLabels;
import org.kframework.builtin.Sorts;
import org.kframework.compile.AddSortInjections;
import org.kframework.compile.ComputeTransitiveFunctionDependencies;
import org.kframework.compile.ConfigurationInfoFromModule;
import org.kframework.compile.ExpandMacros;
import org.kframework.compile.RefreshRules;
import org.kframework.compile.RewriteToTop;
import org.kframework.definition.Module;
import org.kframework.definition.ModuleComment;
import org.kframework.definition.NonTerminal;
import org.kframework.definition.Production;
import org.kframework.definition.ProductionItem;
import org.kframework.definition.Rule;
import org.kframework.definition.Sentence;
import org.kframework.kil.Attribute;
import org.kframework.kil.Attributes;
import org.kframework.kil.loader.Constants;
import org.kframework.kompile.KompileOptions;
import org.kframework.kore.InjectedKLabel;
import org.kframework.kore.K;
import org.kframework.kore.KApply;
import org.kframework.kore.KAs;
import org.kframework.kore.KLabel;
import org.kframework.kore.KORE;
import org.kframework.kore.KRewrite;
import org.kframework.kore.KSequence;
import org.kframework.kore.KToken;
import org.kframework.kore.KVariable;
import org.kframework.kore.Sort;
import org.kframework.kore.VisitK;
import org.kframework.parser.concrete2kore.generator.RuleGrammarGenerator;
import org.kframework.utils.StringUtil;
import org.kframework.utils.errorsystem.KEMException;
import org.kframework.utils.file.FileUtil;
import scala.Option;
import scala.Tuple2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;

import static org.kframework.Collections.*;
import static org.kframework.definition.Constructors.*;
import static org.kframework.kore.KORE.*;

public class ModuleToKORE {
    public static final String ONE_PATH_OP = "weakExistsFinally";
    public static final String ALL_PATH_OP = "weakAlwaysFinally";
    private final Module module;
    private final BiMap<String, String> kToKoreLabelMap = HashBiMap.create();
    private final FileUtil files;
    private final StringBuilder sb = new StringBuilder();
    private final Set<String> impureFunctions = new HashSet<>();
    private final Map<String, List<Set<Integer>>> polyKLabels = new HashMap<>();
    private final KLabel topCellInitializer;
    private final Set<String> mlBinders = new HashSet<>();
    private final KompileOptions options;

    public ModuleToKORE(Module module, FileUtil files, KLabel topCellInitializer, KompileOptions options) {
        this.module = module;
        this.files = files;
        this.topCellInitializer = topCellInitializer;
        this.options = options;
    }
    private static final boolean METAVAR = false;

    public String convert(boolean heatCoolEq) {
        ConfigurationInfoFromModule configInfo = new ConfigurationInfoFromModule(module);
        Sort topCell = configInfo.getRootCell();
        String prelude = files.loadFromKBase("include/kore/prelude.kore");
        sb.append("[topCellInitializer{}(");
        convert(topCellInitializer, false);
        sb.append("())]\n\n");
        sb.append(prelude);
        Map<String, Boolean> attributes = new HashMap<>();
        sb.append("\n");
        for (Sort sort : iterable(module.definedSorts())) {
            Att att = module.sortAttributesFor().get(sort).getOrElse(() -> KORE.Att());
            collectAttributes(attributes, att);
        }
        for (Production prod : iterable(module.sortedProductions())) {
            Att att = prod.att();
            collectAttributes(attributes, att);
        }
        for (Rule r : iterable(module.sortedRules())) {
            Att att = r.att();
            collectAttributes(attributes, att);
        }
        sb.append("module ");
        convert(module.name());
        sb.append("\n\n// imports\n");
        sb.append("  import K []\n\n// sorts\n");
        Set<String> collectionSorts = new HashSet<>();
        collectionSorts.add("SET.Set");
        collectionSorts.add("MAP.Map");
        collectionSorts.add("LIST.List");
        collectionSorts.add("ARRAY.Array");
        for (Sort sort : iterable(module.definedSorts())) {
            if (sort.equals(Sorts.K()) || sort.equals(Sorts.KItem())) {
                continue;
            }
            sb.append("  ");
            Att att = module.sortAttributesFor().get(sort).getOrElse(() -> KORE.Att());
            if (att.contains(Attribute.HOOK_KEY)) {
                if (collectionSorts.contains(att.get(Attribute.HOOK_KEY))) {
                    if (att.get(Attribute.HOOK_KEY).equals("ARRAY.Array")) {
                        att = att.remove("element");
                        att = att.remove("unit");
                        att = att.remove(Attribute.HOOK_KEY);
                    } else {
                        Production concatProd = stream(module.productionsForSort().apply(sort)).filter(p -> p.att().contains("element")).findAny().get();
                        att = att.add("element", K.class, KApply(KLabel(concatProd.att().get("element"))));
                        att = att.add("concat", K.class, KApply(concatProd.klabel().get()));
                        att = att.add("unit", K.class, KApply(KLabel(concatProd.att().get("unit"))));
                        sb.append("hooked-");
                    }
                } else {
                    sb.append("hooked-");
                }
            }
            sb.append("sort ");
            convert(sort, false);
            sb.append(" ");
            convert(attributes, att);
            sb.append("\n");
        }

        SetMultimap<KLabel, Rule> functionRules = HashMultimap.create();
        for (Rule rule : iterable(module.sortedRules())) {
            K left = RewriteToTop.toLeft(rule.body());
            if (left instanceof KApply) {
                KApply kapp = (KApply) left;
                Production prod = production(kapp);
                if (prod.att().contains(Attribute.FUNCTION_KEY) || rule.att().contains(Attribute.ANYWHERE_KEY) || ExpandMacros.isMacro(rule)) {
                    functionRules.put(kapp.klabel(), rule);
                }
            }
        }
        ComputeTransitiveFunctionDependencies deps = new ComputeTransitiveFunctionDependencies(module);
        Set<KLabel> impurities = functionRules.keySet().stream().filter(lbl -> module.attributesFor().get(lbl).getOrElse(() -> Att()).contains(Attribute.IMPURE_KEY)).collect(Collectors.toSet());
        impurities.addAll(deps.ancestors(impurities));

        sb.append("\n// symbols\n");
        Set<Production> overloads = new HashSet<>();
        for (Production lesser : iterable(module.overloads().elements())) {
            for (Production greater : iterable(module.overloads().relations().get(lesser).getOrElse(() -> Collections.<Production>Set()))) {
                overloads.add(greater);
            }
        }
        for (Production prod : iterable(module.sortedProductions())) {
            if (isBuiltinProduction(prod)) {
                continue;
            }
            prod = computePolyProd(prod);
            if (prod.klabel().isEmpty()) {
                continue;
            }
            if (impurities.contains(prod.klabel().get())) {
                impureFunctions.add(prod.klabel().get().name());
            }
            sb.append("  ");
            if (isFunction(prod) && prod.att().contains(Attribute.HOOK_KEY) && isRealHook(prod.att())) {
                sb.append("hooked-");
            }
            sb.append("symbol ");
            convert(prod.klabel().get(), true);
            String conn;
            sb.append("(");
            conn = "";
            for (NonTerminal nt : iterable(prod.nonterminals())) {
                Sort sort = nt.sort();
                sb.append(conn);
                convert(sort, prod);
                conn = ", ";
            }
            sb.append(") : ");
            convert(prod.sort(), prod);
            sb.append(" ");
            convert(attributes, addKoreAttributes(prod, functionRules, impurities, overloads));
            sb.append("\n");
        }
        sb.append("\n// generated axioms\n");
        Set<Tuple2<Production, Production>> noConfusion = new HashSet<>();
        for (Production prod : iterable(module.sortedProductions())) {
            if (isBuiltinProduction(prod)) {
                continue;
            }
            prod = computePolyProd(prod);
            if (prod.isSubsort()) {
                Production finalProd = prod;
                functionalPattern(prod, () -> {
                    sb.append("inj{");
                    convert(finalProd.getSubsortSort(), finalProd);
                    sb.append(", ");
                    convert(finalProd.sort(), finalProd);
                    sb.append("} (From:");
                    convert(finalProd.getSubsortSort(), finalProd);
                    sb.append(")");
                });
                sb.append(" [subsort{");
                convert(prod.getSubsortSort(), prod);
                sb.append(", ");
                convert(prod.sort(), prod);
                sb.append("}()] // subsort\n");
                continue;
            }
            if (prod.klabel().isEmpty()) {
                continue;
            }
            if (prod.att().contains(Attribute.ASSOCIATIVE_KEY)) {
                // s(s(K1,K2),K3) = s(K1,s(K2,K3))
                if (prod.arity() != 2) {
                    throw KEMException.compilerError("Found a non-binary production with the assoc attribute", prod);
                }
                if (!(prod.sort().equals(prod.nonterminal(0).sort()) && prod.sort().equals(prod.nonterminal(1).sort()))) {
                    throw KEMException.compilerError("Found an associative production with ill formed sorts", prod);
                }
                sb.append("  axiom");
                convertParams(prod.klabel(), true);
                sb.append(" \\equals{");
                convert(prod.sort(), prod);
                sb.append(", R} (");
                convert(prod.klabel().get(), prod);
                sb.append("(");
                convert(prod.klabel().get(), prod);
                sb.append("(K1:");
                convert(prod.sort(), prod);
                sb.append(",K2:");
                convert(prod.sort(), prod);
                sb.append("),K3:");
                convert(prod.sort(), prod);
                sb.append("),");
                convert(prod.klabel().get(), prod);
                sb.append("(K1:");
                convert(prod.sort(), prod);
                sb.append(",");
                convert(prod.klabel().get(), prod);
                sb.append("(K2:");
                convert(prod.sort(), prod);
                sb.append(",K3:");
                convert(prod.sort(), prod);
                sb.append("))) [assoc{}()] // associativity\n");
            }
            if (prod.att().contains(Attribute.COMMUTATIVE_KEY)) {
                // s(K1, K2) = s(K2, K1)
                if (prod.arity() != 2) {
                    throw KEMException.compilerError("Found a non-binary production with the comm attribute", prod);
                }
                if (!(prod.nonterminal(0).sort().equals(prod.nonterminal(1).sort()))) {
                    throw KEMException.compilerError("Found a commutative production with ill formed sorts", prod);
                }
                Sort childSort = prod.nonterminal(0).sort();
                sb.append("  axiom");
                convertParams(prod.klabel(), true);
                sb.append(" \\equals{");
                convert(prod.sort(), prod);
                sb.append(", R} (");
                convert(prod.klabel().get(), prod);
                sb.append("(K1:");
                convert(childSort, prod);
                sb.append(",K2:");
                convert(childSort, prod);
                sb.append("),");
                convert(prod.klabel().get(), prod);
                sb.append("(K2:");
                convert(childSort, prod);
                sb.append(",K1:");
                convert(childSort, prod);
                sb.append(")) [comm{}()] // commutativity\n");
            }
            if (prod.att().contains(Attribute.IDEMPOTENT_KEY)) {
                // s(K, K) = K
                if (prod.arity() != 2) {
                    throw KEMException.compilerError("Found a non-binary production with the assoc attribute", prod);
                }
                if (!(prod.sort().equals(prod.nonterminal(0).sort()) && prod.sort().equals(prod.nonterminal(1).sort()))) {
                    throw KEMException.compilerError("Found an associative production with ill formed sorts", prod);
                }
                sb.append("  axiom");
                convertParams(prod.klabel(), true);
                sb.append(" \\equals{");
                convert(prod.sort(), prod);
                sb.append(", R} (");
                convert(prod.klabel().get(), prod);
                sb.append("(K:");
                convert(prod.sort(), prod);
                sb.append(",K:");
                convert(prod.sort(), prod);
                sb.append("),K:");
                convert(prod.sort(), prod);
                sb.append(") [idem{}()] // idempotency\n");
            }
            if (isFunction(prod) && prod.att().contains(Attribute.UNIT_KEY)) {
                // s(K, unit) = K
                // s(unit, K) = K
                if (prod.arity() != 2) {
                    throw KEMException.compilerError("Found a non-binary production with the assoc attribute", prod);
                }
                if (!(prod.sort().equals(prod.nonterminal(0).sort()) && prod.sort().equals(prod.nonterminal(1).sort()))) {
                    throw KEMException.compilerError("Found an associative production with ill formed sorts", prod);
                }
                KLabel unit = KLabel(prod.att().get(Attribute.UNIT_KEY));
                sb.append("  axiom");
                convertParams(prod.klabel(), true);
                sb.append("\\equals{");
                convert(prod.sort(), prod);
                sb.append(", R} (");
                convert(prod.klabel().get(), prod);
                sb.append("(K:");
                convert(prod.sort(), prod);
                sb.append(",");
                convert(unit, false);
                sb.append("()),K:");
                convert(prod.sort(), prod);
                sb.append(") [unit{}()] // right unit\n");

                sb.append("  axiom");
                convertParams(prod.klabel(), true);
                sb.append("\\equals{");
                convert(prod.sort(), prod);
                sb.append(", R} (");
                convert(prod.klabel().get(), prod);
                sb.append("(");
                convert(unit, false);
                sb.append("(),K:");
                convert(prod.sort(), prod);
                sb.append("),K:");
                convert(prod.sort(), prod);
                sb.append(") [unit{}()] // left unit\n");
            }
            if (isFunctional(prod, functionRules, impurities)) {
                // exists y . f(...) = y
                Production finalProd = prod;
                functionalPattern(prod, () -> applyPattern(finalProd, "K"));
                sb.append(" [functional{}()] // functional\n");
            }
            if (isConstructor(prod, functionRules, impurities)) {
                // c(x1,x2,...) /\ c(y1,y2,...) -> c(x1/\y2,x2/\y2,...)
                if (prod.arity() > 0) {
                    sb.append("  axiom");
                    convertParams(prod.klabel(), false);
                    sb.append("\\implies{");
                    convert(prod.sort(), prod);
                    sb.append("} (\\and{");
                    convert(prod.sort(), prod);
                    sb.append("} (");
                    applyPattern(prod, "X");
                    sb.append(", ");
                    applyPattern(prod, "Y");
                    sb.append("), ");
                    convert(prod.klabel().get(), prod);
                    sb.append("(");
                    String conn = "";
                    for (int i = 0; i < prod.arity(); i++) {
                        sb.append(conn);
                        sb.append("\\and{");
                        convert(prod.nonterminal(i).sort(), prod);
                        sb.append("} (X").append(i).append(":");
                        convert(prod.nonterminal(i).sort(), prod);
                        sb.append(", Y").append(i).append(":");
                        convert(prod.nonterminal(i).sort(), prod);
                        sb.append(")");
                        conn = ", ";
                    }
                    sb.append(")) [constructor{}()] // no confusion same constructor\n");
                }
                for (Production prod2 : iterable(module.productionsForSort().apply(prod.sort()).toSeq().sorted(Production.ord()))) {
                    // !(cx(x1,x2,...) /\ cy(y1,y2,...))
                    prod2 = computePolyProd(prod2);
                    if (prod2.klabel().isEmpty() || noConfusion.contains(Tuple2.apply(prod, prod2)) || prod.equals(prod2) || !isConstructor(prod2, functionRules, impurities) || isBuiltinProduction(prod2)) {
                        // TODO (traiansf): add no confusion axioms for constructor vs inj.
                        continue;
                    }
                    noConfusion.add(Tuple2.apply(prod, prod2));
                    noConfusion.add(Tuple2.apply(prod2, prod));
                    sb.append("  axiom");
                    convertParams(prod.klabel(), false);
                    sb.append("\\not{");
                    convert(prod.sort(), prod);
                    sb.append("} (\\and{");
                    convert(prod.sort(), prod);
                    sb.append("} (");
                    applyPattern(prod, "X");
                    sb.append(", ");
                    applyPattern(prod2, "Y");
                    sb.append(")) [constructor{}()] // no confusion different constructors\n");

                }
            }
        }
        for (Sort sort : iterable(module.definedSorts())) {
            sb.append("  axiom{} ");
            boolean hasToken = false;
            int numTerms = 0;
            for (Production prod : iterable(mutable(module.productionsForSort()).getOrDefault(sort, Set()).toSeq().sorted(Production.ord()))) {
                prod = computePolyProd(prod);
                if (isFunction(prod) || prod.isSubsort() || isBuiltinProduction(prod)) {
                    continue;
                }
                if (prod.klabel().isEmpty() && !((prod.att().contains("token") && !hasToken) || prod.isSubsort())) {
                    continue;
                }
                numTerms++;
                sb.append("\\or{");
                convert(sort, false);
                sb.append("} (");
                if (prod.att().contains("token") && !hasToken) {
                    convertTokenProd(sort);
                    hasToken = true;
                } else if (prod.klabel().isDefined()) {
                    for (int i = 0; i < prod.arity(); i++) {
                        sb.append("\\exists{");
                        convert(sort, false);
                        sb.append("} (X").append(i).append(":");
                        convert(prod.nonterminal(i).sort(), prod);
                        sb.append(", ");
                    }
                    convert(prod.klabel().get(), prod);
                    sb.append("(");
                    String conn = "";
                    for (int i = 0; i < prod.arity(); i++) {
                        sb.append(conn).append("X").append(i).append(":");
                        convert(prod.nonterminal(i).sort(), prod);
                        conn = ", ";
                    }
                    sb.append(")");
                    for (int i = 0; i < prod.arity(); i++) {
                        sb.append(")");
                    }
                }
                sb.append(", ");
            }
            for (Sort s : iterable(module.definedSorts())) {
                if (module.subsorts().lessThan(s, sort)) {
                    numTerms++;
                    sb.append("\\or{");
                    convert(sort, false);
                    sb.append("} (");
                    sb.append("\\exists{");
                    convert(sort, false);
                    sb.append("} (Val:");
                    convert(s, false);
                    sb.append(", inj{");
                    convert(s, false);
                    sb.append(", ");
                    convert(sort, false);
                    sb.append("} (Val:");
                    convert(s, false);
                    sb.append("))");
                    sb.append(", ");
                }
            }
            Att sortAtt = module.sortAttributesFor().get(sort).getOrElse(() -> KORE.Att());
            if (!hasToken && sortAtt.contains("token")) {
                numTerms++;
                sb.append("\\or{");
                convert(sort, false);
                sb.append("} (");
                convertTokenProd(sort);
                sb.append(", ");
                hasToken = true;
            }
            sb.append("\\bottom{");
            convert(sort, false);
            sb.append("}()");
            for (int i = 0; i < numTerms; i++) {
                sb.append(")");
            }
            sb.append(" [constructor{}()] // no junk");
            if (hasToken && !METAVAR) {
                sb.append(" (TODO: fix bug with \\dv)");
            }
            sb.append("\n");
        }
        for (Production lesser : iterable(module.overloads().elements())) {
            for (Production greater : iterable(module.overloads().relations().get(lesser).getOrElse(() -> Collections.<Production>Set()))) {
                sb.append("  axiom{R} \\equals{");
                convert(greater.sort(), greater);
                sb.append(", R} (");
                convert(greater.klabel().get(), greater);
                sb.append("(");
                String conn = "";
                for (int i = 0; i < greater.nonterminals().size(); i++) {
                    sb.append(conn);
                    if (greater.nonterminal(i).sort().equals(lesser.nonterminal(i).sort())) {
                        sb.append("K").append(i).append(":");
                        convert(greater.nonterminal(i).sort(), greater);
                    } else {
                        sb.append("inj{");
                        convert(lesser.nonterminal(i).sort(), lesser);
                        sb.append(", ");
                        convert(greater.nonterminal(i).sort(), greater);
                        sb.append("} (K").append(i).append(":");
                        convert(lesser.nonterminal(i).sort(), lesser);
                        sb.append(")");
                    }
                    conn = ",";
                }
                sb.append("), inj{");
                convert(lesser.sort(), lesser);
                sb.append(", ");
                convert(greater.sort(), greater);
                sb.append("} (");
                convert(lesser.klabel().get(), lesser);
                sb.append("(");
                conn = "";
                for (int i = 0; i < lesser.nonterminals().size(); i++) {
                    sb.append(conn);
                    sb.append("K").append(i).append(":");
                    convert(lesser.nonterminal(i).sort(), lesser);
                    conn = ",";
                }
                sb.append("))) [overload{}(");
                convert(greater.klabel().get(), greater);
                sb.append("(), ");
                convert(lesser.klabel().get(), lesser);
                sb.append("())] // overloaded production\n");
            }
        }
        sb.append("\n// rules\n");
        for (Rule rule : iterable(module.sortedRules())) {
            convertRule(rule, heatCoolEq, topCell, attributes, functionRules, false, false);
        }
        sb.append("endmodule ");
        convert(attributes, module.att());
        sb.append("\n");
        return sb.toString();
    }

    private boolean isRealHook(Att att) {
      String hook = att.get(Attribute.HOOK_KEY);
      if (hook.startsWith("ARRAY.")) {
        return false;
      }
      if (options.hookNamespaces.stream().anyMatch(ns -> hook.startsWith(ns + "."))) {
        return true;
      }
      return Hooks.namespaces.stream().anyMatch(ns -> hook.startsWith(ns + "."));
    }

    private static boolean isBuiltinProduction(Production prod) {
        return prod.klabel().nonEmpty() && ConstructorChecks.isBuiltinLabel(prod.klabel().get());
    }

    public String convertSpecificationModule(Module definition, Module spec, boolean allPathReachability) {
        sb.setLength(0); // reset string writer
        ConfigurationInfoFromModule configInfo = new ConfigurationInfoFromModule(definition);
        Sort topCell = configInfo.getRootCell();
        sb.append("[]\n");
        sb.append("module ");
        convert(spec.name());
        sb.append("\n\n// imports\n");
        sb.append("import ");
        convert(definition.name());
        sb.append(" []\n");
        sb.append("\n\n// claims\n");
        for (Sentence sentence : iterable(spec.sentencesExcept(definition))) {
            assert sentence instanceof Rule || sentence instanceof ModuleComment
                : "Unexpected non-rule claim " + sentence.toString();
            if (sentence instanceof Rule) {
                convertRule((Rule) sentence, false, topCell, new HashMap<>(), HashMultimap.create(), true, allPathReachability);
            }
        }
        sb.append("endmodule ");
        convert(new HashMap<>(), spec.att());
        sb.append("\n");
        return sb.toString();
    }

    private void convertRule(Rule rule, boolean heatCoolEq, Sort topCellSort, Map<String, Boolean> consideredAttributes, SetMultimap<KLabel, Rule> functionRules, boolean rulesAsClaims, boolean allPathReachability) {
        // injections should already be present, but this is an ugly hack to get around the
        // cache persistence issue that means that Sort attributes on k terms might not be present.
        rule = new AddSortInjections(module).addInjections(rule);
        ConstructorChecks constructorChecks = new ConstructorChecks(module);
        boolean equation = false;
        boolean owise = false;
        boolean kore = rule.att().contains(Attribute.KORE_KEY);
        Production production = null;
        Sort productionSort = null;
        List<Sort> productionSorts = null;
        KLabel productionLabel = null;
        List<K> leftChildren = null;
        K left = RewriteToTop.toLeft(rule.body());
        boolean constructorBased = constructorChecks.isConstructorBased(left);
        if (left instanceof KApply) {
            production = production((KApply) left, true);
            productionSort = production.sort();
            productionSorts = stream(production.items())
                    .filter(i -> i instanceof NonTerminal)
                    .map(i -> (NonTerminal) i)
                    .map(NonTerminal::sort).collect(Collectors.toList());
            productionLabel = production.klabel().get();
            if (isFunction(production) || rule.att().contains(Attribute.ANYWHERE_KEY) && !kore) {
                leftChildren = ((KApply) left).items();
                equation = true;
            } else if ((rule.att().contains("heat") || rule.att().contains("cool")) && heatCoolEq) {
                equation = true;
                productionSort = topCellSort;
            }
            owise = rule.att().contains("owise");
        }
        sb.append("// ");
        sb.append(rule.toString());
        sb.append("\n");
        if (equation) {
            if (!constructorBased) {
                if (!consideredAttributes.containsKey(Attribute.SIMPLIFICATION_KEY)) {
                    consideredAttributes.put(Attribute.SIMPLIFICATION_KEY, false);
                }
                if (!rule.att().contains(Attribute.SIMPLIFICATION_KEY)) {
                    rule = rule.withAtt(rule.att().add(Attribute.SIMPLIFICATION_KEY));
                }
            }
            sb.append("  axiom{R");
            Option<Set> sortParams = rule.att().getOption("sortParams", Set.class);
            if (sortParams.nonEmpty()) {
                for (Object sortParamName : sortParams.get())
                    sb.append("," + sortParamName);
            }
            sb.append("} ");
            if (owise) {
                Set<String> varNames = vars(rule).stream().map(KVariable::name).collect(Collectors.toSet());
                sb.append("\\implies{R} (\n    \\and{R} (\n      \\not{R} (\n        ");
                for (Rule notMatching : RefreshRules.refresh(functionRules.get(productionLabel), varNames)) {
                    if (notMatching.att().contains("owise")) {
                        continue;
                    }
                    sb.append("\\or{R} (\n");
                    Set<KVariable> vars = vars(notMatching);
                    for (KVariable var : vars) {
                        sb.append("          \\exists{R} (");
                        convert(var);
                        sb.append(",\n          ");
                    }
                    sb.append("  \\and{R} (");
                    sb.append("\n              ");
                    convertSideCondition(notMatching.requires());
                    sb.append(",\n              ");

                    K notMatchingLeft = RewriteToTop.toLeft(notMatching.body());
                    assert notMatchingLeft instanceof KApply : "expecting KApply but got " + notMatchingLeft.getClass();
                    List<K> notMatchingChildren = ((KApply) notMatchingLeft).items();
                    assert notMatchingChildren.size() == leftChildren.size() : "assuming function with fixed arity";
                    for (int childIdx = 0; childIdx < leftChildren.size(); childIdx++) {
                        sb.append("\\and{R} (");
                        sb.append("\n                ");
                        sb.append("\\ceil{");
                        Sort childSort = productionSorts.get(childIdx);
                        convert(childSort, false);
                        sb.append(", R} (");
                        sb.append("\n                  ");
                        sb.append("\\and{");
                        convert(childSort, false);
                        sb.append("} (\n                    ");
                        convert(leftChildren.get(childIdx));
                        sb.append(",\n                    ");
                        convert(notMatchingChildren.get(childIdx));
                        sb.append("\n                )),");
                    }
                    sb.append("\n                \\top{R} ()");
                    sb.append("\n              ");
                    for (int childIdx = 0; childIdx < leftChildren.size(); childIdx++) {
                        sb.append(')');
                    }
                    sb.append("\n          )");
                    for (KVariable ignored : vars) {
                        sb.append(")");
                    }
                    sb.append(",\n          ");
                }
                sb.append("\\bottom{R}()");
                sb.append("\n        ");
                for (Rule notMatching : functionRules.get(productionLabel)) {
                    if (notMatching.att().contains("owise")) {
                        continue;
                    }
                    sb.append(")");
                }
                sb.append("\n      ),\n      ");
                convertSideCondition(rule.requires());
                sb.append("\n    ),\n    \\and{R} (\n      \\equals{");
                convert(productionSort, false);
                sb.append(",R} (\n        ");
                K right = RewriteToTop.toRight(rule.body());
                convert(left);
                sb.append(",\n        ");
                convert(right);
                sb.append("),\n      ");
                convertSideCondition(rule.ensures());
                sb.append("))\n  ");
                convert(consideredAttributes, rule.att());
                sb.append("\n\n");
            } else {
                sb.append("\\implies{R} (\n    ");
                convertSideCondition(rule.requires());
                sb.append(",\n    \\and{R} (\n      \\equals{");
                convert(productionSort, false);
                sb.append(",R} (\n        ");
                K right = RewriteToTop.toRight(rule.body());
                convert(left);
                sb.append(",\n        ");
                convert(right);
                sb.append("),\n      ");
                convertSideCondition(rule.ensures());
                sb.append("))\n  ");
                convert(consideredAttributes, rule.att());
                sb.append("\n\n");
            }
        } else if (kore) {
            if (rulesAsClaims) {
                sb.append("  claim{} ");
            } else {
                sb.append("  axiom{} ");
            }
            convert(left);
            sb.append("\n  ");
            convert(consideredAttributes, rule.att());
            sb.append("\n\n");
        } else if (!ExpandMacros.isMacro(rule)) {
            if (rulesAsClaims) {
                sb.append("  claim{} ");
            } else {
                sb.append("  axiom{} ");
            }
            if (owise) {
                // hack to deal with the strategy axiom for now
                sb.append("\\implies{");
                convert(topCellSort, false);
                sb.append("}(\\bottom{");
                convert(topCellSort, false);
                sb.append("}(),");
            }
            K right = RewriteToTop.toRight(rule.body());
            if (rulesAsClaims) {
                sb.append("\\implies{");
            } else {
                sb.append("\\rewrites{");
            }
            convert(topCellSort, false);
            sb.append("} (\n    ");
            sb.append("  \\and{");
            convert(topCellSort, false);
            sb.append("} (\n      ");
            convertSideCondition(rule.requires(), topCellSort);
            sb.append(", ");
            convert(left);
            sb.append("), ");
            if (rulesAsClaims) {
                if (allPathReachability) {
                    sb.append(ALL_PATH_OP + "{");
                } else {
                    sb.append(ONE_PATH_OP + "{");
                }
                convert(topCellSort, false);
                sb.append("} (\n      ");
            }
            sb.append("\\and{");
            convert(topCellSort, false);
            sb.append("} (\n      ");
            convertSideCondition(rule.ensures(), topCellSort);
            sb.append(", ");
            convert(right);
            sb.append("))");
            if (rulesAsClaims) {
                sb.append(')');
            }
            if (owise) {
                sb.append(')');
            }
            sb.append("\n  ");
            convert(consideredAttributes, rule.att());
            sb.append("\n\n");
        }
    }

    private void functionalPattern(Production prod, Runnable functionPattern) {
        sb.append("  axiom");
        convertParams(prod.klabel(), true);
        sb.append(" \\exists{R} (Val:");
        convert(prod.sort(), prod);
        sb.append(", \\equals{");
        convert(prod.sort(), prod);
        sb.append(", R} (");
        sb.append("Val:");
        convert(prod.sort(), prod);
        sb.append(", ");
        functionPattern.run();
        sb.append("))");
    }

    private void applyPattern(Production prod, String varName) {
        convert(prod.klabel().get(), prod);
        sb.append("(");
        String conn = "";
        for (int i = 0; i < prod.arity(); i++) {
            sb.append(conn);
            sb.append(varName).append(i).append(":");
            convert(prod.nonterminal(i).sort(), prod);
            conn = ", ";
        }
        sb.append(')');
    }

    private void convertTokenProd(Sort sort) {
        if (METAVAR) {
            sb.append("\\exists{");
            convert(sort, false);
            sb.append("} (#Str:#String{}, \\dv{");
            convert(sort, false);
            sb.append("}(#Str:#String{}))");
        } else {
            sb.append("\\top{");
            convert(sort, false);
            sb.append("}()");
        }
    }

    private void convertParams(Option<KLabel> maybeKLabel, boolean hasR) {
        sb.append("{");
        String conn = "";
        if (hasR) {
            sb.append("R");
            if (maybeKLabel.isDefined()) {
                conn = ", ";
            }
        }
        if (maybeKLabel.isDefined()) {
            for (Sort param : iterable(maybeKLabel.get().params())) {
                sb.append(conn);
                convert(param, true);
                conn = ", ";
            }
        }
        sb.append("}");
    }

    private boolean isConstructor(Production prod, SetMultimap<KLabel, Rule> functionRules, Set<KLabel> impurities) {
        Att att = addKoreAttributes(prod, functionRules, impurities, java.util.Collections.emptySet());
        return att.contains("constructor");
    }

    private boolean isFunctional(Production prod, SetMultimap<KLabel, Rule> functionRules, Set<KLabel> impurities) {
        Att att = addKoreAttributes(prod, functionRules, impurities, java.util.Collections.emptySet());
        return att.contains("functional");
    }

    private Att addKoreAttributes(Production prod, SetMultimap<KLabel, Rule> functionRules, Set<KLabel> impurities, Set<Production> overloads) {
        boolean isFunctional = !isFunction(prod);
        boolean isConstructor = !isFunction(prod);
        isConstructor &= !prod.att().contains(Attribute.ASSOCIATIVE_KEY);
        isConstructor &= !prod.att().contains(Attribute.COMMUTATIVE_KEY);
        isConstructor &= !prod.att().contains(Attribute.IDEMPOTENT_KEY);
        isConstructor &= !(prod.att().contains(Attribute.FUNCTION_KEY) && prod.att().contains(Attribute.UNIT_KEY));

        // Later we might set !isConstructor because there are anywhere rules,
        // but if a symbol is a constructor at this point, then it is still
        // injective.
        boolean isInjective = isConstructor;

        boolean isMacro = false;
        boolean isAnywhere = false;
        isAnywhere |= overloads.contains(prod);
        for (Rule r : functionRules.get(prod.klabel().get())) {
            isMacro |= ExpandMacros.isMacro(r);
            isAnywhere |= r.att().contains(Attribute.ANYWHERE_KEY);
        }
        isConstructor &= !isMacro;
        isConstructor &= !isAnywhere;

        Att att = prod.att().remove("constructor");
        if (att.contains(Attribute.HOOK_KEY) && !isRealHook(att)) {
            att = att.remove("hook");
        }
        if (isConstructor) {
            att = att.add("constructor");
        }
        if (isFunctional) {
            att = att.add("functional");
        }
        if (isAnywhere) {
            att = att.add("anywhere");
        }
        if (isInjective) {
            att = att.add("injective");
        }
        if (isMacro) {
            att = att.add("macro");
        }
        return att;
    }

    private boolean isFunction(Production prod) {
        Production realProd = prod.att().get(Constants.ORIGINAL_PRD, Production.class);
        if (!realProd.att().contains(Attribute.FUNCTION_KEY)) {
            return false;
        }
        return true;
    }

    private Set<KVariable> vars(Rule rule) {
        Set<KVariable> res = new HashSet<>();
        VisitK visitor = new VisitK() {
            @Override
            public void apply(KVariable k) {
                res.add(k);
            }
        };
        visitor.apply(rule.requires());
        visitor.apply(RewriteToTop.toLeft(rule.body()));
        return res;
    }

    private void convertSideCondition(K k) {
        if (k.equals(BooleanUtils.TRUE)) {
            sb.append("\\top{R}()");
        } else {
            sb.append("\\equals{SortBool{},R}(\n        ");
            convert(k);
            sb.append(",\n        \\dv{SortBool{}}(\"true\"))");
        }
    }

    private void convertSideCondition(K k, Sort result) {
        if (k.equals(BooleanUtils.TRUE)) {
            sb.append("\\top{");
            convert(result, false);
            sb.append("}()");
        } else {
            sb.append("\\equals{SortBool{},");
            convert(result, false);
            sb.append("}(\n        ");
            convert(k);
            sb.append(",\n        \\dv{SortBool{}}(\"true\"))");
        }
    }

    private Production computePolyProd(Production prod) {
        return computePolyProd(prod, null);
    }

    private Production computePolyProd(Production prod, KApply k) {
        if (prod.klabel().isEmpty() || !prod.att().contains("poly"))
            return prod.withAtt(prod.att().add(Constants.ORIGINAL_PRD, Production.class, prod));
        List<Set<Integer>> poly = RuleGrammarGenerator.computePositions(prod);
        String labelName = prod.klabel().get().name();
        if (prod.att().contains(Attribute.ML_BINDER_KEY)) {
            mlBinders.add(labelName);
        }
        polyKLabels.put(labelName, poly);
        List<Sort> params = new ArrayList<>();
        List<NonTerminal> children = new ArrayList<>(mutable(prod.nonterminals()));
        Sort returnSort = prod.sort();
        for (int i = 0; i < poly.size(); i++) {
            Set<Integer> positions = poly.get(i);
            Sort sort = Sort("S" + i);
            if (k != null) {
                int firstPos = positions.iterator().next();
                if (firstPos == 0) {
                    sort = k.att().get(Sort.class);
                } else {
                    sort = k.klist().items().get(firstPos - 1).att().get(Sort.class);
                }
            }
            params.add(sort);
            for (int j : positions) {
                if (j == 0) {
                    returnSort = sort;
                } else {
                    children.set(j - 1, NonTerminal(sort));
                }
            }
        }
        List<ProductionItem> items = new ArrayList<>();
        int i = 0;
        for (ProductionItem item : iterable(prod.items())) {
            if (item instanceof NonTerminal) {
                items.add(children.get(i));
                i++;
            } else {
                items.add(item);
            }
        }
        return Production(KLabel(labelName, immutable(params)), returnSort, immutable(items),
                prod.att().add(Constants.ORIGINAL_PRD, Production.class, prod));
    }

    private KLabel computePolyKLabel(KApply k) {
        String labelName = k.klabel().name();
        List<Set<Integer>> poly = new ArrayList<>(polyKLabels.get(labelName));
        if (mlBinders.contains(labelName)) { // ML binders are not parametric in the variable so we remove it
            poly.remove(0);
        }
        List<Sort> params = new ArrayList<>();
        for (Set<Integer> positions : poly) {
            int pos = positions.iterator().next();
            Sort sort;
            if (pos == 0) {
                sort = k.att().get(Sort.class);
            } else {
                sort = k.items().get(pos-1).att().get(Sort.class);
            }
            params.add(sort);
        }
        return KLabel(labelName, immutable(params));
    }


    private void collectAttributes(Map<String, Boolean> attributes, Att att) {
        for (Tuple2<Tuple2<String, String>, ?> attribute : iterable(att.att())) {
            String name = attribute._1._1;
            Object val = attribute._2;
            String strVal = val.toString();
            if (strVal.equals("")) {
                if (!attributes.containsKey(name)) {
                    attributes.put(name, false);
                }
            } else {
                attributes.put(name, true);
            }
        }
    }

    private static final Production INJ_PROD = Production(KLabel(KLabels.INJ), Sort("K"), Seq(NonTerminal(Sort("K"))), Att().add("poly", "1; 0"));


    private Production production(KApply term) {
        return production(term, false);
    }

    private Production production(KApply term, boolean instantiatePolySorts) {
        KLabel klabel = term.klabel();
        if (klabel.name().equals(KLabels.INJ))
            return computePolyProd(INJ_PROD, instantiatePolySorts ? term : null);
        Option<scala.collection.Set<Production>> prods = module.productionsFor().get(klabel);
        assert(prods.nonEmpty());
        assert(prods.get().size() == 1);
        return computePolyProd(prods.get().head(), instantiatePolySorts ? term : null);
    }

    private String convertBuiltinLabel(String klabel) {
      switch(klabel) {
      case "#False":
        return "\\bottom";
      case "#True":
        return "\\top";
      case "#Or":
        return "\\or";
      case "#And":
        return "\\and";
      case "#Not":
        return "\\not";
      case "#Ceil":
        return "\\ceil";
      case "#Equals":
        return "\\equals";
      case "#Implies":
        return "\\implies";
      case "#Exists":
        return "\\exists";
      case "#Forall":
        return "\\forall";
      case "#AG":
        return "allPathGlobally";
      default:
        throw KEMException.compilerError("Unsuppored kore connective in rule: " + klabel);
      }
    }

    private void convert(KLabel klabel, boolean var) {
        if (klabel.name().equals(KLabels.INJ)) {
            sb.append(klabel.name());
        } else if (ConstructorChecks.isBuiltinLabel(klabel)) {
            sb.append(convertBuiltinLabel(klabel.name()));
        } else {
            sb.append("Lbl");
            convert(klabel.name());
        }
        sb.append("{");
        String conn = "";
        for (Sort param : iterable(klabel.params())) {
            sb.append(conn);
            convert(param, var);
            conn = ", ";
        }
        sb.append("}");
    }

    private void convert(KLabel klabel, Production prod) {
        if (klabel.name().equals(KLabels.INJ)) {
            sb.append(klabel.name());
        } else {
            sb.append("Lbl");
            convert(klabel.name());
        }
        sb.append("{");
        String conn = "";
        for (Sort param : iterable(klabel.params())) {
            sb.append(conn);
            convert(param, prod);
            conn = ", ";
        }
        sb.append("}");
    }

    private void convert(Sort sort, Production prod) {
        convert(sort, prod.klabel().isDefined() && prod.klabel().get().params().contains(sort));
    }

    private void convert(Sort sort, boolean var) {
        if (sort.name().equals(AddSortInjections.SORTPARAM_NAME)) {
            String sortVar = sort.params().headOption().get().name();
            sb.append(sortVar);
            return;
        }
        sb.append("Sort");
        convert(sort.name());
        if (!var) {
            sb.append("{");
            String conn = "";
            for (Sort param : iterable(sort.params())) {
                sb.append(conn);
                convert(param.name());
                conn = ", ";
            }
            sb.append("}");
        }
    }

    private void convert(Map<String, Boolean> attributes, Att att) {
        sb.append("[");
        String conn = "";
        for (Tuple2<Tuple2<String, String>, ?> attribute : iterable(att.att())) {
            String name = attribute._1._1;
            String clsName = attribute._1._2;
            Object val = attribute._2;
            String strVal = val.toString();
            sb.append(conn);
            if (clsName.equals(K.class.getName())) {
                convert(name);
                sb.append("{}(");
                convert((K) val);
                sb.append(")");
            } else if (attributes.get(name) != null && attributes.get(name)) {
                convert(name);
                sb.append("{}(");
                sb.append(StringUtil.enquoteKString(strVal));
                sb.append(")");
            } else {
                convert(name);
                sb.append("{}()");
            }
            conn = ", ";
        }
        sb.append("]");
    }

    private static String[] asciiReadableEncodingKoreCalc() {
        String[] koreEncoder = Arrays.copyOf(StringUtil.asciiReadableEncodingDefault, StringUtil.asciiReadableEncodingDefault.length);
        koreEncoder[0x2d] = "-";
        koreEncoder[0x3c] = "-LT-";
        koreEncoder[0x3e] = "-GT-";
        koreEncoder[0x40] = "-AT-";
        koreEncoder[0x5e] = "Xor-";
        return koreEncoder;
    }

    private static final Pattern identChar = Pattern.compile("[A-Za-z0-9\\-]");
    public static String[] asciiReadableEncodingKore = asciiReadableEncodingKoreCalc();

    private void convert(String name) {
        if (kToKoreLabelMap.containsKey(name)) {
            sb.append(kToKoreLabelMap.get(name));
            return;
        }
        switch(name) {
        case "module":
        case "endmodule":
        case "sort":
        case "hooked-sort":
        case "symbol":
        case "hooked-symbol":
        case "alias":
        case "axiom":
            sb.append(name).append("'Kywd'");
            kToKoreLabelMap.put(name, name + "'Kywd'");
            return;
        default: break;
        }
        StringBuilder buffer = new StringBuilder();
        StringUtil.encodeStringToAlphanumeric(buffer, name, asciiReadableEncodingKore, identChar, "'");
        sb.append(buffer);
        kToKoreLabelMap.put(name, buffer.toString());
    }

    @Override
    public String toString() { return sb.toString(); }

    public Set<K> collectAnonymousVariables(K k){
        Set <K> anonymousVariables = new HashSet<>();
        new VisitK() {
            @Override
            public void apply(KApply k) {
                if (mlBinders.contains(k.klabel().name()) && k.items().get(0).att().contains("anonymous")){
                    throw KEMException.internalError("Nested quantifier over anonymous variables.");
                }
                for (K item : k.items()) {
                    apply(item);
                }
            }

            @Override
            public void apply(KVariable k) {
                if (k.att().contains("anonymous")) {
                    anonymousVariables.add(k);
                }
            }

        }.apply(k);
        return anonymousVariables;
    }

    public void convert(K k) {
        new VisitK() {
            @Override
            public void apply(KApply k) {
                if (impureFunctions.contains(k.klabel().name())) {
                    throw KEMException.internalError("Cannot yet translate impure function to kore: " + k.klabel().name(), k);
                }
                KLabel label = k.klabel();
                if (polyKLabels.containsKey(k.klabel().name())) {
                    label = computePolyKLabel(k);
                }
                String conn = "";
                if (mlBinders.contains(k.klabel().name()) && k.items().get(0).att().contains("anonymous")){
                    // Handle #Forall _ / #Exists _
                    Set<K> anonymousVariables = collectAnonymousVariables(k.items().get(1));

                    // Quantify over all anonymous variables.
                    for (K variable : anonymousVariables) {
                        sb.append(conn);
                        convert(label, false);
                        sb.append("(");
                        apply(variable);
                        conn = ",";
                    }

                    // We assume that mlBinder only has two children.
                    sb.append(conn);
                    apply(k.items().get(1));

                    for (int i = 0; i < anonymousVariables.size(); i++) {
                        sb.append(")");
                    }
                } else {
                    convert(label, false);
                    sb.append("(");
                    for (K item : k.items()) {
                        sb.append(conn);
                        apply(item);
                        conn = ",";
                    }
                    sb.append(")");
                }
            }

            @Override
            public void apply(KToken k) {
                sb.append("\\dv{");
                convert(k.sort(), false);
                sb.append("}(");
                if (module.sortAttributesFor().get(k.sort()).getOrElse(() -> Att.empty()).getOptional("hook").orElse("").equals("STRING.String")) {
                    sb.append(k.s());
                } else {
                    sb.append(StringUtil.enquoteKString(k.s()));
                }
                sb.append(")");
            }

            @Override
            public void apply(KSequence k) {
                for (int i = 0; i < k.items().size(); i++) {
                    K item = k.items().get(i);
                    boolean isList = item.att().get(Sort.class).equals(Sorts.K());
                    if (i == k.items().size() - 1) {
                        if (isList) {
                            apply(item);
                        } else {
                            sb.append("kseq{}(");
                            apply(item);
                            sb.append(",dotk{}())");
                        }
                    } else {
                        if (item.att().get(Sort.class).equals(Sorts.K())) {
                            sb.append("append{}(");
                        } else {
                            sb.append("kseq{}(");
                        }
                        apply(item);
                        sb.append(",");
                    }
                }
                if (k.items().size() == 0) {
                    sb.append("dotk{}()");
                }
                for (int i = 0; i < k.items().size() - 1; i++) {
                    sb.append(")");
                }
            }

            @Override
            public void apply(KVariable k) {
                boolean setVar = k.name().startsWith("@");
                if (setVar) {
                    sb.append('@');
                }
                sb.append("Var");
                String name = setVar ? k.name().substring(1) : k.name();
                convert(name);
                sb.append(":");
                convert(k.att().getOptional(Sort.class).orElse(Sorts.K()), false);
            }

            @Override
            public void apply(KRewrite k) {
                sb.append("\\rewrites{");
                convert(k.att().get(Sort.class), false);
                sb.append("}(");
                apply(k.left());
                sb.append(",");
                apply(k.right());
                sb.append(")");
            }

            @Override
            public void apply(KAs k) {
                Sort sort = k.att().get(Sort.class);
                sb.append("\\and{");
                convert(sort, false);
                sb.append("}(");
                apply(k.pattern());
                sb.append(",");
                apply(k.alias());
                sb.append(")");
            }

            @Override
            public void apply(InjectedKLabel k) {
                throw KEMException.internalError("Cannot yet translate #klabel to kore", k);
            }
        }.apply(k);
    }

    public BiMap<String, String> getKToKoreLabelMap() {
        return kToKoreLabelMap;
    }
}
