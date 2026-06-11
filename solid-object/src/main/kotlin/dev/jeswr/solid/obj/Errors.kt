package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.Term

/**
 * Errors raised while projecting RDF terms into typed values.
 *
 * Only the *required* and *strict-decode* paths throw; the optional and set
 * accessors are lenient (a value that fails to decode is skipped, never
 * surfaced as an error), which suits messy real-world pod data.
 */
public sealed class WrapperException(message: String) : Exception(message) {
    /** A `RequiredFrom` accessor found no object for `subject predicate ?o`. */
    public class MissingRequired(public val subject: Term, public val predicate: String) :
        WrapperException("missing required object for <$predicate> on $subject")

    /** A literal mapper was applied to a term that is not a literal. */
    public class NotALiteral(public val term: Term) :
        WrapperException("expected a literal but found $term")

    /** An IRI mapper was applied to a term that is not an IRI. */
    public class NotAnIRI(public val term: Term) :
        WrapperException("expected an IRI but found $term")

    /**
     * A literal's lexical form could not be parsed as the requested type
     * (e.g. `LiteralAs.int` on `"banana"`). Mirrors `@rdfjs/wrapper`'s
     * `LiteralDatatypeError`.
     */
    public class LiteralDatatype(public val lexicalForm: String, public val expected: String) :
        WrapperException("literal \"$lexicalForm\" is not a valid $expected")
}
