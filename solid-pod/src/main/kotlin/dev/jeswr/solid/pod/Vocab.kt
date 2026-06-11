package dev.jeswr.solid.pod

/** Well-known vocabulary IRIs. All resolve at their real W3C namespaces. */
internal object Vocab {
    const val LDP_CONTAINS = "http://www.w3.org/ns/ldp#contains"
    const val LDP_BASIC_CONTAINER = "http://www.w3.org/ns/ldp#BasicContainer"

    const val PIM_STORAGE = "http://www.w3.org/ns/pim/space#storage"
    const val PIM_STORAGE_CLASS = "http://www.w3.org/ns/pim/space#Storage"

    const val FOAF_NAME = "http://xmlns.com/foaf/0.1/name"

    const val SOLID_OIDC_ISSUER = "http://www.w3.org/ns/solid/terms#oidcIssuer"
    const val SOLID_PUBLIC_TYPE_INDEX = "http://www.w3.org/ns/solid/terms#publicTypeIndex"
    const val SOLID_PRIVATE_TYPE_INDEX = "http://www.w3.org/ns/solid/terms#privateTypeIndex"
    const val SOLID_TYPE_REGISTRATION = "http://www.w3.org/ns/solid/terms#TypeRegistration"
    const val SOLID_INSTANCE = "http://www.w3.org/ns/solid/terms#instance"
    const val SOLID_INSTANCE_CONTAINER = "http://www.w3.org/ns/solid/terms#instanceContainer"

    const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

    const val ACL_NAMESPACE = "http://www.w3.org/ns/auth/acl#"
    const val ACP_NAMESPACE = "http://www.w3.org/ns/solid/acp#"
    const val ACL_AUTHORIZATION = ACL_NAMESPACE + "Authorization"
    const val ACL_ACCESS_TO = ACL_NAMESPACE + "accessTo"
    const val ACL_DEFAULT = ACL_NAMESPACE + "default"
    const val ACL_MODE = ACL_NAMESPACE + "mode"
    const val ACL_AGENT = ACL_NAMESPACE + "agent"
    const val ACL_AGENT_CLASS = ACL_NAMESPACE + "agentClass"
    const val ACL_AGENT_GROUP = ACL_NAMESPACE + "agentGroup"
    const val ACL_ORIGIN = ACL_NAMESPACE + "origin"
}
