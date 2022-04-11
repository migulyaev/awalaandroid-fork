package tech.relaycorp.awaladroid.endpoint

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.relaycorp.awaladroid.test.MockContextTestCase
import tech.relaycorp.relaynet.SessionKeyPair
import tech.relaycorp.relaynet.issueDeliveryAuthorization
import tech.relaycorp.relaynet.issueEndpointCertificate
import tech.relaycorp.relaynet.ramf.RecipientAddressType
import tech.relaycorp.relaynet.testing.pki.KeyPairSet
import tech.relaycorp.relaynet.testing.pki.PDACertPath
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import tech.relaycorp.relaynet.wrappers.privateAddress
import tech.relaycorp.relaynet.wrappers.x509.Certificate

internal class PrivateThirdPartyEndpointTest : MockContextTestCase() {
    private val thirdPartyEndpointCertificate = issueEndpointCertificate(
        KeyPairSet.PDA_GRANTEE.public,
        KeyPairSet.PRIVATE_GW.private,
        ZonedDateTime.now().plusDays(1),
        PDACertPath.PRIVATE_GW,
    )
    private val pda = issueDeliveryAuthorization(
        subjectPublicKey = KeyPairSet.PRIVATE_ENDPOINT.public,
        issuerPrivateKey = KeyPairSet.PDA_GRANTEE.private,
        validityEndDate = ZonedDateTime.now().plusDays(1),
        issuerCertificate = thirdPartyEndpointCertificate,
    )

    private val sessionKey = SessionKeyPair.generate().sessionKey

    @Test
    fun load_successful() = runBlockingTest {
        whenever(storage.privateThirdParty.get(any())).thenReturn(
            PrivateThirdPartyEndpointData(
                KeyPairSet.PRIVATE_ENDPOINT.public,
                AuthorizationBundle(
                    PDACertPath.PDA.serialize(),
                    listOf(
                        PDACertPath.PRIVATE_ENDPOINT.serialize(),
                        PDACertPath.PRIVATE_GW.serialize(),
                    )
                )
            )
        )
        val firstAddress = UUID.randomUUID().toString()
        val thirdAddress = UUID.randomUUID().toString()

        with(PrivateThirdPartyEndpoint.load(thirdAddress, firstAddress)!!) {
            assertEquals(firstAddress, firstPartyEndpointAddress)
            assertEquals(PDACertPath.PRIVATE_ENDPOINT.subjectPrivateAddress, address)
            assertEquals(PDACertPath.PDA, pda)
            assertEquals(listOf(PDACertPath.PRIVATE_ENDPOINT, PDACertPath.PRIVATE_GW), pdaChain)
        }

        verify(storage.privateThirdParty).get("${firstAddress}_$thirdAddress")
    }

    @Test
    fun load_nonExistent() = runBlockingTest {
        whenever(storage.privateThirdParty.get(any())).thenReturn(null)

        assertNull(
            PrivateThirdPartyEndpoint.load(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString()
            )
        )
    }

    @Test
    fun import_successful() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()

        val authBundle = AuthorizationBundle(
            pda.serialize(),
            listOf(thirdPartyEndpointCertificate.serialize())
        )
        val endpoint = PrivateThirdPartyEndpoint.import(
            KeyPairSet.PDA_GRANTEE.public.encoded,
            authBundle,
            sessionKey,
        )

        assertEquals(
            firstPartyEndpoint.privateAddress,
            endpoint.firstPartyEndpointAddress
        )
        assertEquals(
            KeyPairSet.PDA_GRANTEE.public.privateAddress,
            endpoint.address
        )
        assertEquals(
            KeyPairSet.PDA_GRANTEE.public,
            endpoint.identityKey
        )
        assertEquals(pda, endpoint.pda)
        assertArrayEquals(
            arrayOf(thirdPartyEndpointCertificate),
            endpoint.pdaChain.toTypedArray()
        )

        verify(storage.privateThirdParty).set(
            "${firstPartyEndpoint.privateAddress}_${endpoint.privateAddress}",
            PrivateThirdPartyEndpointData(
                KeyPairSet.PDA_GRANTEE.public,
                authBundle
            )
        )

        assertEquals(sessionKey, sessionPublicKeystore.retrieve(endpoint.privateAddress))
    }

    @Test
    fun import_invalidIdentityKey() = runBlockingTest {
        val exception = assertThrows(InvalidThirdPartyEndpoint::class.java) {
            runBlockingTest {
                PrivateThirdPartyEndpoint.import(
                    "123456".toByteArray(),
                    AuthorizationBundle(thirdPartyEndpointCertificate.serialize(), emptyList()),
                    sessionKey,
                )
            }
        }

        assertEquals("Identity key is not a well-formed RSA public key", exception.message)
    }

    @Test
    fun import_invalidFirstParty() = runBlockingTest {
        val firstPartyCert = PDACertPath.PRIVATE_ENDPOINT
        val exception = assertThrows(UnknownFirstPartyEndpointException::class.java) {
            runBlockingTest {
                PrivateThirdPartyEndpoint.import(
                    KeyPairSet.PDA_GRANTEE.public.encoded,
                    AuthorizationBundle(
                        firstPartyCert.serialize(),
                        emptyList(),
                    ),
                    sessionKey,
                )
            }
        }

        assertEquals(
            "First-party endpoint ${firstPartyCert.subjectPrivateAddress} is not registered",
            exception.message
        )
    }

    @Test
    fun import_wrongAuthorizationIssuer() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()

        val unrelatedKeyPair = generateRSAKeyPair()
        val unrelatedCertificate = issueEndpointCertificate(
            unrelatedKeyPair.public,
            unrelatedKeyPair.private,
            ZonedDateTime.now().plusDays(1)
        )

        val authorization = issueDeliveryAuthorization(
            subjectPublicKey = firstPartyEndpoint.identityCertificate.subjectPublicKey,
            issuerPrivateKey = KeyPairSet.PRIVATE_ENDPOINT.private,
            validityEndDate = ZonedDateTime.now().plusDays(1),
            issuerCertificate = PDACertPath.PRIVATE_ENDPOINT
        )

        val exception = assertThrows(InvalidAuthorizationException::class.java) {
            runBlockingTest {
                PrivateThirdPartyEndpoint.import(
                    KeyPairSet.PDA_GRANTEE.public.encoded,
                    AuthorizationBundle(
                        authorization.serialize(),
                        listOf(unrelatedCertificate.serialize())
                    ),
                    sessionKey,
                )
            }
        }

        assertEquals("PDA was not issued by third-party endpoint", exception.message)
    }

    @Test
    fun import_invalidAuthorization() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()

        val authorization = issueDeliveryAuthorization(
            firstPartyEndpoint.identityCertificate.subjectPublicKey,
            KeyPairSet.PRIVATE_ENDPOINT.private,
            validityEndDate = ZonedDateTime.now().minusDays(1),
            issuerCertificate = PDACertPath.PRIVATE_ENDPOINT,
            validityStartDate = ZonedDateTime.now().minusDays(2)
        )

        val exception = assertThrows(InvalidAuthorizationException::class.java) {
            runBlockingTest {
                PrivateThirdPartyEndpoint.import(
                    KeyPairSet.PDA_GRANTEE.public.encoded,
                    AuthorizationBundle(authorization.serialize(), emptyList()),
                    sessionKey,
                )
            }
        }

        assertEquals("PDA is invalid", exception.message)
    }

    @Test
    fun dataSerialization() {
        val pda = PDACertPath.PDA
        val identityKey = KeyPairSet.PRIVATE_ENDPOINT.public
        val dataSerialized = PrivateThirdPartyEndpointData(
            identityKey,
            AuthorizationBundle(
                pda.serialize(),
                listOf(PDACertPath.PRIVATE_GW.serialize(), PDACertPath.PUBLIC_GW.serialize())
            )
        ).serialize()
        val data = PrivateThirdPartyEndpointData.deserialize(dataSerialized)

        assertEquals(identityKey, data.identityKey)
        assertEquals(pda, Certificate.deserialize(data.authBundle.pdaSerialized))
        assertArrayEquals(
            arrayOf(PDACertPath.PRIVATE_GW, PDACertPath.PUBLIC_GW),
            data.authBundle.pdaChainSerialized.map { Certificate.deserialize(it) }.toTypedArray()
        )
    }

    @Test
    fun delete() = runBlockingTest {
        val channel = createEndpointChannel(RecipientAddressType.PRIVATE)
        val endpoint = channel.thirdPartyEndpoint as PrivateThirdPartyEndpoint
        val firstPartyEndpoint = channel.firstPartyEndpoint

        endpoint.delete()

        verify(storage.privateThirdParty)
            .delete("${endpoint.pda.subjectPrivateAddress}_${endpoint.privateAddress}")
        assertEquals(0, privateKeyStore.sessionKeys[firstPartyEndpoint.privateAddress]!!.size)
        assertEquals(0, sessionPublicKeystore.keys.size)
    }
}
