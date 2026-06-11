package dev.jeswr.solid.rdf

/** Core RDF vocabulary IRIs. All resolve at their real W3C namespaces. */
public object RDF {
    public const val NAMESPACE: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    public const val TYPE: String = NAMESPACE + "type"
    public const val LANG_STRING: String = NAMESPACE + "langString"
    public const val FIRST: String = NAMESPACE + "first"
    public const val REST: String = NAMESPACE + "rest"
    public const val NIL: String = NAMESPACE + "nil"
}

/** XML Schema datatype IRIs. */
public object XSD {
    public const val NAMESPACE: String = "http://www.w3.org/2001/XMLSchema#"
    public const val STRING: String = NAMESPACE + "string"
    public const val INTEGER: String = NAMESPACE + "integer"
    public const val DECIMAL: String = NAMESPACE + "decimal"
    public const val DOUBLE: String = NAMESPACE + "double"
    public const val BOOLEAN: String = NAMESPACE + "boolean"
    public const val DATE_TIME: String = NAMESPACE + "dateTime"
    public const val DATE: String = NAMESPACE + "date"
    public const val ANY_URI: String = NAMESPACE + "anyURI"
}
