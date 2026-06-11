package dev.jeswr.solid.rdf

/**
 * An RDF literal: a lexical form paired with a datatype IRI and, for
 * language-tagged strings, a BCP 47 language tag.
 *
 * Invariant: when [language] is non-null the datatype is `rdf:langString`;
 * otherwise it is any other datatype IRI (`xsd:string` for plain literals).
 */
public class Literal private constructor(
    /** The lexical form (the raw string content, unescaped). */
    public val lexicalForm: String,
    /** The datatype IRI (e.g. `http://www.w3.org/2001/XMLSchema#string`). */
    public val datatypeIRI: String,
    /** The BCP 47 language tag, lowercased, for `rdf:langString` literals. */
    public val language: String?,
) {
    public companion object {
        /**
         * A typed literal. Passing `rdf:langString` without a tag is
         * normalised to `xsd:string`.
         */
        public operator fun invoke(lexicalForm: String, datatype: String = XSD.STRING): Literal =
            Literal(
                lexicalForm = lexicalForm,
                datatypeIRI = if (datatype == RDF.LANG_STRING) XSD.STRING else datatype,
                language = null,
            )

        /** A language-tagged string (`rdf:langString`). */
        public fun lang(lexicalForm: String, language: String): Literal =
            Literal(lexicalForm, RDF.LANG_STRING, language.lowercase())

        /** An `xsd:integer` literal. */
        public fun integer(value: Long): Literal = Literal(value.toString(), XSD.INTEGER)

        /** An `xsd:integer` literal. */
        public fun integer(value: Int): Literal = integer(value.toLong())

        /** An `xsd:boolean` literal. */
        public fun boolean(value: Boolean): Literal =
            Literal(if (value) "true" else "false", XSD.BOOLEAN)
    }

    override fun equals(other: Any?): Boolean =
        other is Literal &&
            other.lexicalForm == lexicalForm &&
            other.datatypeIRI == datatypeIRI &&
            other.language == language

    override fun hashCode(): Int {
        var result = lexicalForm.hashCode()
        result = 31 * result + datatypeIRI.hashCode()
        result = 31 * result + (language?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = when {
        language != null -> "\"$lexicalForm\"@$language"
        datatypeIRI == XSD.STRING -> "\"$lexicalForm\""
        else -> "\"$lexicalForm\"^^<$datatypeIRI>"
    }
}

/**
 * An RDF term: IRI, blank node, or literal.
 *
 * Blank node identifiers are scoped to the graph they appear in and carry no
 * meaning beyond identity within that graph.
 */
public sealed interface Term {
    /** A named node (IRI). */
    public data class IRI(public val value: String) : Term

    /** A blank node, identified by a graph-local label. */
    public data class BlankNode(public val id: String) : Term

    /** A literal value. */
    public data class LiteralTerm(public val literal: Literal) : Term

    /** The IRI string when this term is an IRI, else null. */
    public val iriValue: String?
        get() = (this as? IRI)?.value

    /** The literal when this term is a literal, else null. */
    public val literalValue: Literal?
        get() = (this as? LiteralTerm)?.literal

    /** True for IRIs and blank nodes (the terms allowed in subject position). */
    public val isResource: Boolean
        get() = this is IRI || this is BlankNode

    public companion object {
        /** Shorthand for an IRI term. */
        public fun iri(value: String): Term = IRI(value)

        /** Shorthand for a blank node term. */
        public fun blankNode(id: String): Term = BlankNode(id)

        /** Shorthand for a literal term. */
        public fun literal(literal: Literal): Term = LiteralTerm(literal)

        /** Shorthand for a plain `xsd:string` literal term. */
        public fun string(value: String): Term = LiteralTerm(Literal(value))
    }
}

/**
 * An RDF triple. By RDF semantics the subject must be an IRI or blank node and
 * the predicate an IRI; this is asserted at construction time.
 */
public data class Triple(
    public val subject: Term,
    public val predicate: Term,
    public val `object`: Term,
) {
    init {
        require(subject.isResource) { "RDF triple subject must be an IRI or blank node" }
        require(predicate.iriValue != null) { "RDF triple predicate must be an IRI" }
    }

    public companion object {
        /** Convenience for the common all-IRI subject/predicate case. */
        public operator fun invoke(subject: String, predicate: String, `object`: Term): Triple =
            Triple(Term.IRI(subject), Term.IRI(predicate), `object`)
    }
}
