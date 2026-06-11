package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.Literal
import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.XSD
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Projects an RDF [Term] into a typed value (the read / `…As` direction). The
 * [GraphBox] is supplied so object mappers ([TermAs.instance]) can construct
 * nested wrappers over the same graph; literal and IRI mappers ignore it.
 *
 * Decoders throw [WrapperException] when the term cannot satisfy the mapping;
 * the optional and set accessors catch and skip, the required accessor
 * propagates.
 */
public fun interface TermDecoder<out V> {
    public fun decode(term: Term, graph: GraphBox): V
}

/**
 * Builds an RDF [Term] from a typed value (the write / `…From` direction). The
 * [GraphBox] is supplied for symmetry; the built-in encoders do not need it.
 */
public fun interface TermEncoder<in V> {
    public fun encode(value: V, graph: GraphBox): Term
}

private fun requireLiteral(term: Term): Literal =
    term.literalValue ?: throw WrapperException.NotALiteral(term)

/**
 * Decoders that read literal terms as scalars. Pair with [LiteralFrom] on the
 * write side.
 */
public object LiteralAs {
    /** The lexical form of any literal, as `String` (the datatype is ignored). */
    public val string: TermDecoder<String> = TermDecoder { term, _ -> requireLiteral(term).lexicalForm }

    /** An integer literal parsed as `Long`. */
    public val long: TermDecoder<Long> = TermDecoder { term, _ ->
        val lex = requireLiteral(term).lexicalForm
        lex.toLongOrNull() ?: throw WrapperException.LiteralDatatype(lex, "xsd:integer")
    }

    /** An integer literal parsed as `Int`. */
    public val int: TermDecoder<Int> = TermDecoder { term, _ ->
        val lex = requireLiteral(term).lexicalForm
        lex.toIntOrNull() ?: throw WrapperException.LiteralDatatype(lex, "xsd:integer")
    }

    /** A numeric literal parsed as `Double` (covers `xsd:double`/`decimal`/`float`). */
    public val double: TermDecoder<Double> = TermDecoder { term, _ ->
        val lex = requireLiteral(term).lexicalForm
        lex.toDoubleOrNull() ?: throw WrapperException.LiteralDatatype(lex, "xsd:double")
    }

    /** An `xsd:boolean` literal (`"true"`/`"false"`/`"1"`/`"0"`). */
    public val boolean: TermDecoder<Boolean> = TermDecoder { term, _ ->
        when (requireLiteral(term).lexicalForm.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw WrapperException.LiteralDatatype(requireLiteral(term).lexicalForm, "xsd:boolean")
        }
    }

    /** An `xsd:date` or `xsd:dateTime` literal parsed as [Instant]. */
    public val instant: TermDecoder<Instant> = TermDecoder { term, _ ->
        val lex = requireLiteral(term).lexicalForm
        DateConvert.parse(lex) ?: throw WrapperException.LiteralDatatype(lex, "xsd:date/dateTime")
    }

    /** The full [Literal] value (lexical form + datatype + language tag). */
    public val literal: TermDecoder<Literal> = TermDecoder { term, _ -> requireLiteral(term) }
}

/**
 * Encoders that build literal terms from scalars. Pair with [LiteralAs] on the
 * read side.
 */
public object LiteralFrom {
    /** An `xsd:string` literal. */
    public val string: TermEncoder<String> = TermEncoder { value, _ -> Term.literal(Literal(value)) }

    /** An `xsd:integer` literal. */
    public val integer: TermEncoder<Long> = TermEncoder { value, _ -> Term.literal(Literal.integer(value)) }

    /** An `xsd:integer` literal from an `Int`. */
    public val integerInt: TermEncoder<Int> = TermEncoder { value, _ -> Term.literal(Literal.integer(value)) }

    /** An `xsd:double` literal. */
    public val double: TermEncoder<Double> = TermEncoder { value, _ ->
        Term.literal(Literal(value.toString(), XSD.DOUBLE))
    }

    /** An `xsd:boolean` literal. */
    public val boolean: TermEncoder<Boolean> = TermEncoder { value, _ -> Term.literal(Literal.boolean(value)) }

    /** An `xsd:dateTime` literal in canonical ISO-8601 (UTC) form. */
    public val dateTime: TermEncoder<Instant> = TermEncoder { value, _ ->
        Term.literal(Literal(DateConvert.dateTimeLexical(value), XSD.DATE_TIME))
    }

    /**
     * An `xsd:date` literal (`YYYY-MM-DD`, UTC).
     *
     * Note: this emits a **correct** `xsd:date` lexical form. The JavaScript
     * `@rdfjs/wrapper` `LiteralFrom.date` is known to emit a `dateTime`
     * lexical under an `xsd:date` datatype, which SHACL rejects; this Kotlin
     * port does not reproduce that bug.
     */
    public val date: TermEncoder<Instant> = TermEncoder { value, _ ->
        Term.literal(Literal(DateConvert.dateLexical(value), XSD.DATE))
    }
}

/** Decoders that read IRI (named-node) terms. Pair with [IRIFrom]. */
public object IRIAs {
    /** The IRI as a `String`. */
    public val string: TermDecoder<String> = TermDecoder { term, _ ->
        term.iriValue ?: throw WrapperException.NotAnIRI(term)
    }

    /** The IRI as a [URI]. */
    public val uri: TermDecoder<URI> = TermDecoder { term, _ ->
        val value = term.iriValue ?: throw WrapperException.NotAnIRI(term)
        try {
            URI(value)
        } catch (e: IllegalArgumentException) {
            throw WrapperException.LiteralDatatype(value, "URI")
        }
    }
}

/** Encoders that build IRI (named-node) terms. Pair with [IRIAs]. */
public object IRIFrom {
    /** An IRI term from a `String`. */
    public val string: TermEncoder<String> = TermEncoder { value, _ -> Term.iri(value) }

    /** An IRI term from a [URI]. */
    public val uri: TermEncoder<URI> = TermEncoder { value, _ -> Term.iri(value.toString()) }
}

/**
 * Decoders that project a term into another [TermWrapper] subclass — the
 * mechanism behind navigation properties (`book.author.name`). Pair with
 * [TermFrom].
 */
public object TermAs {
    /**
     * Construct an instance of the wrapper [factory] over the object term,
     * sharing the same graph. Lets a property return a nested typed model.
     */
    public fun <T : TermWrapper> instance(factory: (Term, GraphBox) -> T): TermDecoder<T> =
        TermDecoder { term, graph -> factory(term, graph) }

    /** The raw [Term], unprojected. */
    public val term: TermDecoder<Term> = TermDecoder { term, _ -> term }
}

/**
 * Encoders that write a nested wrapper (or raw term) back as the object of a
 * triple. Pair with [TermAs].
 */
public object TermFrom {
    /**
     * Use a wrapper's [TermWrapper.term] as the object — i.e. link to it. The
     * nested resource's own triples are assumed already present in the shared
     * graph (assignment links, it does not deep-copy), matching `@rdfjs/wrapper`.
     */
    public fun <T : TermWrapper> instance(): TermEncoder<T> = TermEncoder { wrapper, _ -> wrapper.term }

    /** A raw [Term] as the object. */
    public val term: TermEncoder<Term> = TermEncoder { term, _ -> term }
}

/**
 * `xsd:date` / `xsd:dateTime` lexical ⇄ [Instant], fixed to UTC so output is
 * canonical and parsing is locale-independent.
 */
internal object DateConvert {
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(lexical: String): Instant? {
        // dateTime forms (with or without fractional seconds, with offset/Z).
        runCatching { return Instant.parse(lexical) }
        runCatching {
            return java.time.OffsetDateTime.parse(lexical).toInstant()
        }
        // Plain xsd:date → midnight UTC.
        runCatching {
            return LocalDate.parse(lexical, dateFormatter).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        return null
    }

    fun dateTimeLexical(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    fun dateLexical(instant: Instant): String =
        dateFormatter.format(instant.atZone(ZoneOffset.UTC).toLocalDate())
}
