package dev.jeswr.solid.oidc

import java.math.BigInteger
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint

/**
 * Minimal secp256r1 (P-256) point arithmetic — just enough to derive the public
 * point Q = s·G from a stored private scalar s, so a DPoP key persisted as its
 * 32-byte scalar can be fully reconstructed without a third-party crypto
 * library. Standard short-Weierstrass affine formulas over the curve's prime
 * field; the curve parameters come from the JCA [ECParameterSpec] so there is
 * no hard-coded constant to get wrong.
 */
internal object EcMath {
    fun scalarMultiply(k: BigInteger, params: ECParameterSpec): ECPoint {
        val p = (params.curve.field as java.security.spec.ECFieldFp).p
        val a = params.curve.a
        val g = params.generator
        var result: ECPoint? = null // point at infinity
        var addend = g
        var n = k
        while (n.signum() > 0) {
            if (n.testBit(0)) {
                result = add(result, addend, a, p)
            }
            addend = add(addend, addend, a, p)
            n = n.shiftRight(1)
        }
        return result ?: ECPoint.POINT_INFINITY
    }

    private fun add(p1: ECPoint?, p2: ECPoint?, a: BigInteger, prime: BigInteger): ECPoint {
        if (p1 == null || p1 == ECPoint.POINT_INFINITY) return p2 ?: ECPoint.POINT_INFINITY
        if (p2 == null || p2 == ECPoint.POINT_INFINITY) return p1
        val x1 = p1.affineX
        val y1 = p1.affineY
        val x2 = p2.affineX
        val y2 = p2.affineY

        val lambda: BigInteger = if (x1 == x2) {
            if ((y1 + y2).mod(prime).signum() == 0) return ECPoint.POINT_INFINITY
            // doubling: (3x^2 + a) / (2y)
            val num = (BigInteger.valueOf(3) * x1 * x1 + a).mod(prime)
            val den = (BigInteger.TWO * y1).modInverse(prime)
            (num * den).mod(prime)
        } else {
            val num = (y2 - y1).mod(prime)
            val den = (x2 - x1).mod(prime).modInverse(prime)
            (num * den).mod(prime)
        }
        val x3 = (lambda * lambda - x1 - x2).mod(prime)
        val y3 = (lambda * (x1 - x3) - y1).mod(prime)
        return ECPoint(x3, y3)
    }
}
