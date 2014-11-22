// Copyright (c) 2014 K Team. All Rights Reserved.

package org.kframework.kore;

import static org.kframework.kore.outer.Constructors.*;

import java.util.List;

import scala.collection.Seq;

/**
 *
 * Helper constructors for KORE classes. The class is meant to be imported
 * statically.
 *
 * @see org.kframework.kore.InterfaceTest
 *
 */

public class Constructors {
    private static Attributes emptyAttributes = Attributes();

    public static Attributes Attributes(K... ks) {
        org.kframework.kore.KList kList = KList(ks);
        return new Attributes(kList.toSet());
    }

    public static Attributes Attributes(KList klist) {
        return new Attributes(klist.toSet());
    }

    public static KString KString(String s) {
        return new KString(s);
    }

    public static KLabel Hole() {
        return Hole$.MODULE$;
    }

    public static KLabel KBagLabel() {
        return KBag$.MODULE$;
    }

    public static KBag KBag(K... ks) {
        return new KBag(KList.fromJava(ks));
    }

    public static KBag KBag(List<K> ks) {
        return new KBag(org.kframework.kore.outer.Constructors.KList(ks));
    }

    public static Sort Sort(String name) {
        return new Sort(name);
    }

    public static KLabel KLabel(String name) {
        return new ConcreteKLabel(name);
    }

    public static KList KList(K... ks) {
        return KList$.MODULE$.fromJava(ks);
    }

    //
    // public static KList KList(Iterable<K> ks) {
    // return KList$.MODULE$.apply(immutable(ks));
    // }
    //
    // public static KList KList(K k) {
    // return KList.fromJava(new K[] { k });
    // }

    public static KApply KApply(KLabel klabel, scala.collection.Iterable<K> klist, Attributes att) {
        return new KApply(klabel, klist, att);
    }

    public static KApply KApply(KLabel klabel, scala.collection.Iterable<K> klist) {
        return KApply(klabel, klist, emptyAttributes);
    }

    public static KToken KToken(Sort sort, KString string, Attributes att) {
        return new KUninterpretedToken(sort, string, att);
    }

    public static KToken KToken(Sort sort, KString string) {
        return KToken(sort, string, emptyAttributes);
    }

    public static KVariable KVariable(String name, Attributes att) {
        return new KVariable(name, emptyAttributes);
    }

    public static KVariable KVariable(String name) {
        return KVariable(name, emptyAttributes);
    }

    public static KSequence KSequence(K... ks) {
        return KSequence.fromJava(ks);
    }

    public static KSequence KSequence(List<K> ks) {
        return KSequence(org.kframework.kore.outer.Constructors.KList(ks));
    }

    public static KRewrite KRewrite(K left, K right) {
        return new KRewrite(left, right, emptyAttributes);
    }

    public static KRewrite KRewrite(K left, K right, Attributes att) {
        return new KRewrite(left, right, att);
    }

    @SafeVarargs
    public static <A> Seq<A> Seq(A... es) {
        return immutable(es);
    }
}
