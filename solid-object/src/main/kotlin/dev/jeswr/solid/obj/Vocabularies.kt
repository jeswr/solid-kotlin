package dev.jeswr.solid.obj

// Common RDF vocabularies used by the bundled domain wrappers, and available to
// your own TermWrapper subclasses. IRIs are the real, dereferenceable namespace
// terms — never mint your own; reuse these or another deployed vocabulary.

/** Friend of a Friend — people, profiles, social links. */
public object FOAF {
    public const val NAMESPACE: String = "http://xmlns.com/foaf/0.1/"
    public const val PERSON: String = NAMESPACE + "Person"
    public const val AGENT: String = NAMESPACE + "Agent"
    public const val NAME: String = NAMESPACE + "name"
    public const val NICK: String = NAMESPACE + "nick"
    public const val MBOX: String = NAMESPACE + "mbox"
    public const val IMG: String = NAMESPACE + "img"
    public const val DEPICTION: String = NAMESPACE + "depiction"
    public const val KNOWS: String = NAMESPACE + "knows"
    public const val HOMEPAGE: String = NAMESPACE + "homepage"
}

/** vCard — contact details (names, emails, photos, addresses). */
public object VCARD {
    public const val NAMESPACE: String = "http://www.w3.org/2006/vcard/ns#"
    public const val FN: String = NAMESPACE + "fn"
    public const val HAS_EMAIL: String = NAMESPACE + "hasEmail"
    public const val HAS_PHOTO: String = NAMESPACE + "hasPhoto"
    public const val VALUE: String = NAMESPACE + "value"
}

/** Schema.org — broad cross-domain terms. */
public object SCHEMA {
    public const val NAMESPACE: String = "https://schema.org/"
    public const val NAME: String = NAMESPACE + "name"
    public const val EMAIL: String = NAMESPACE + "email"
    public const val IMAGE: String = NAMESPACE + "image"
}

/** ActivityStreams 2.0 — actors and content. */
public object AS {
    public const val NAMESPACE: String = "https://www.w3.org/ns/activitystreams#"
    public const val NAME: String = NAMESPACE + "name"
    public const val IMAGE: String = NAMESPACE + "image"
}

/** RDF Schema — labels, comments, class/property structure. */
public object RDFS {
    public const val NAMESPACE: String = "http://www.w3.org/2000/01/rdf-schema#"
    public const val LABEL: String = NAMESPACE + "label"
    public const val COMMENT: String = NAMESPACE + "comment"
    public const val SEE_ALSO: String = NAMESPACE + "seeAlso"
}

/** OWL — used here for `owl:sameAs` profile linking. */
public object OWL {
    public const val NAMESPACE: String = "http://www.w3.org/2002/07/owl#"
    public const val SAME_AS: String = NAMESPACE + "sameAs"
}

/** Solid terms — OIDC issuer, type indexes. */
public object SOLID {
    public const val NAMESPACE: String = "http://www.w3.org/ns/solid/terms#"
    public const val OIDC_ISSUER: String = NAMESPACE + "oidcIssuer"
    public const val PUBLIC_TYPE_INDEX: String = NAMESPACE + "publicTypeIndex"
    public const val PRIVATE_TYPE_INDEX: String = NAMESPACE + "privateTypeIndex"
}

/** PIM / Solid workspace — storage (pod root) discovery. */
public object PIM {
    public const val NAMESPACE: String = "http://www.w3.org/ns/pim/space#"
    public const val STORAGE: String = NAMESPACE + "storage"
    public const val PREFERENCES_FILE: String = NAMESPACE + "preferencesFile"
}

/** Linked Data Platform — containers and their members. */
public object LDP {
    public const val NAMESPACE: String = "http://www.w3.org/ns/ldp#"
    public const val CONTAINER: String = NAMESPACE + "Container"
    public const val BASIC_CONTAINER: String = NAMESPACE + "BasicContainer"
    public const val RESOURCE: String = NAMESPACE + "Resource"
    public const val CONTAINS: String = NAMESPACE + "contains"
}
