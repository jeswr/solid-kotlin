package dev.jeswr.solid.obj

import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Kotlin property delegates over the accessor pattern — the idiomatic twist on
 * top of the Swift-parity [OptionalFrom]/[RequiredAs]/etc. functions.
 *
 * A model can read:
 * ```kotlin
 * class Person(term: Term, graph: GraphBox) : TermWrapper(term, graph) {
 *     val name: String? by optional(FOAF.NAME, LiteralAs.string)
 *     var email: String? by optionalRW(VCARD.EMAIL, LiteralAs.string, LiteralFrom.string)
 *     val knows: WrappedSet<Person> by set(FOAF.KNOWS, TermAs.instance(::Person), TermFrom.instance())
 * }
 * ```
 *
 * The delegates simply forward to the same accessor objects, so delegate-style
 * and explicit-accessor-style stay perfectly consistent.
 */

/** Read-only `OptionalFrom` delegate (`val x: V? by optional(...)`). */
public fun <V> TermWrapper.optional(
    predicate: String,
    decoder: TermDecoder<V>,
): ReadOnlyProperty<TermWrapper, V?> =
    ReadOnlyProperty { thisRef, _ -> OptionalFrom.subjectPredicate(thisRef, predicate, decoder) }

/**
 * Read-only `OptionalFrom` fallback-chain delegate
 * (`val name: String? by optionalChain(listOf(FOAF.NAME, SCHEMA.NAME), LiteralAs.string)`).
 */
public fun <V> TermWrapper.optionalChain(
    predicates: List<String>,
    decoder: TermDecoder<V>,
): ReadOnlyProperty<TermWrapper, V?> =
    ReadOnlyProperty { thisRef, _ -> OptionalFrom.firstSubjectPredicate(thisRef, predicates, decoder) }

/** Read-write optional delegate (`var x: V? by optionalRW(...)`). */
public fun <V> TermWrapper.optionalRW(
    predicate: String,
    decoder: TermDecoder<V>,
    encoder: TermEncoder<V>,
): ReadWriteProperty<TermWrapper, V?> =
    object : ReadWriteProperty<TermWrapper, V?> {
        override fun getValue(thisRef: TermWrapper, property: KProperty<*>): V? =
            OptionalFrom.subjectPredicate(thisRef, predicate, decoder)

        override fun setValue(thisRef: TermWrapper, property: KProperty<*>, value: V?) {
            OptionalAs.`object`(thisRef, predicate, value, encoder)
        }
    }

/** Read-only `RequiredFrom` delegate (`val x: V by required(...)`). */
public fun <V> TermWrapper.required(
    predicate: String,
    decoder: TermDecoder<V>,
): ReadOnlyProperty<TermWrapper, V> =
    ReadOnlyProperty { thisRef, _ -> RequiredFrom.subjectPredicate(thisRef, predicate, decoder) }

/** Read-write required delegate (`var x: V by requiredRW(...)`). */
public fun <V> TermWrapper.requiredRW(
    predicate: String,
    decoder: TermDecoder<V>,
    encoder: TermEncoder<V>,
): ReadWriteProperty<TermWrapper, V> =
    object : ReadWriteProperty<TermWrapper, V> {
        override fun getValue(thisRef: TermWrapper, property: KProperty<*>): V =
            RequiredFrom.subjectPredicate(thisRef, predicate, decoder)

        override fun setValue(thisRef: TermWrapper, property: KProperty<*>, value: V) {
            RequiredAs.`object`(thisRef, predicate, value, encoder)
        }
    }

/** Read-only live-set delegate (`val xs: WrappedSet<V> by set(...)`). */
public fun <V> TermWrapper.set(
    predicate: String,
    decoder: TermDecoder<V>,
    encoder: TermEncoder<V>,
): ReadOnlyProperty<TermWrapper, WrappedSet<V>> =
    ReadOnlyProperty { thisRef, _ -> SetFrom.subjectPredicate(thisRef, predicate, decoder, encoder) }
