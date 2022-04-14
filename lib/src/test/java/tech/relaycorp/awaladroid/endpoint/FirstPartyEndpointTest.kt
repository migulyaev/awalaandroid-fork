package tech.relaycorp.awaladroid.endpoint

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argThat
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyZeroInteractions
import com.nhaarman.mockitokotlin2.whenever
import java.security.PublicKey
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runBlockingTest
import nl.altindag.log.LogCaptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.relaycorp.awaladroid.Awala
import tech.relaycorp.awaladroid.GatewayProtocolException
import tech.relaycorp.awaladroid.RegistrationFailedException
import tech.relaycorp.awaladroid.messaging.OutgoingMessage
import tech.relaycorp.awaladroid.storage.persistence.PersistenceException
import tech.relaycorp.awaladroid.test.FirstPartyEndpointFactory
import tech.relaycorp.awaladroid.test.MockContextTestCase
import tech.relaycorp.awaladroid.test.ThirdPartyEndpointFactory
import tech.relaycorp.awaladroid.test.assertSameDateTime
import tech.relaycorp.awaladroid.test.setAwalaContext
import tech.relaycorp.relaynet.keystores.KeyStoreBackendException
import tech.relaycorp.relaynet.messages.control.PrivateNodeRegistration
import tech.relaycorp.relaynet.pki.CertificationPath
import tech.relaycorp.relaynet.ramf.RecipientAddressType
import tech.relaycorp.relaynet.testing.keystores.MockCertificateStore
import tech.relaycorp.relaynet.testing.keystores.MockPrivateKeyStore
import tech.relaycorp.relaynet.testing.pki.KeyPairSet
import tech.relaycorp.relaynet.testing.pki.PDACertPath
import tech.relaycorp.relaynet.wrappers.privateAddress

internal class FirstPartyEndpointTest : MockContextTestCase() {
    @Test
    fun address() {
        val endpoint = FirstPartyEndpointFactory.build()
        assertEquals(endpoint.identityCertificate.subjectPrivateAddress, endpoint.address)
    }

    @Test
    fun publicKey() {
        val endpoint = FirstPartyEndpointFactory.build()
        assertEquals(endpoint.identityCertificate.subjectPublicKey, endpoint.publicKey)
    }

    @Test
    fun pdaChain() {
        val endpoint = FirstPartyEndpointFactory.build()

        assertTrue(endpoint.identityCertificate in endpoint.pdaChain)
        assertTrue(PDACertPath.PRIVATE_GW in endpoint.pdaChain)
    }

    @Test
    fun register() = runBlockingTest {
        whenever(gatewayClient.registerEndpoint(any())).thenReturn(
            PrivateNodeRegistration(
                PDACertPath.PRIVATE_ENDPOINT,
                PDACertPath.PRIVATE_GW
            )
        )

        val endpoint = FirstPartyEndpoint.register()

        val identityPrivateKey =
            privateKeyStore.retrieveIdentityKey(endpoint.privateAddress)
        assertEquals(endpoint.identityPrivateKey, identityPrivateKey)
        val identityCertificatePath = certificateStore.retrieveLatest(
            endpoint.identityCertificate.subjectPrivateAddress,
            PDACertPath.PRIVATE_GW.subjectPrivateAddress
        )
        assertEquals(PDACertPath.PRIVATE_ENDPOINT, identityCertificatePath!!.leafCertificate)
        verify(storage.gatewayPrivateAddress).set(
            endpoint.privateAddress,
            PDACertPath.PRIVATE_GW.subjectPrivateAddress
        )
    }

    @Test(expected = RegistrationFailedException::class)
    fun register_failed() = runBlockingTest {
        whenever(gatewayClient.registerEndpoint(any())).thenThrow(RegistrationFailedException(""))

        FirstPartyEndpoint.register()

        verifyZeroInteractions(storage)
        assertEquals(0, privateKeyStore.identityKeys.size)
    }

    @Test(expected = GatewayProtocolException::class)
    fun register_failedDueToProtocol(): Unit = runBlockingTest {
        whenever(gatewayClient.registerEndpoint(any())).thenThrow(GatewayProtocolException(""))

        FirstPartyEndpoint.register()

        verifyZeroInteractions(storage)
        assertEquals(0, privateKeyStore.identityKeys.size)
    }

    @Test
    fun register_failedDueToPrivateKeystore(): Unit = runBlockingTest {
        whenever(gatewayClient.registerEndpoint(any())).thenReturn(
            PrivateNodeRegistration(
                PDACertPath.PRIVATE_ENDPOINT,
                PDACertPath.PRIVATE_GW
            )
        )
        val savingException = Exception("Oh noes")
        setAwalaContext(
            Awala.getContextOrThrow().copy(
                privateKeyStore = MockPrivateKeyStore(savingException = savingException)
            )
        )

        val exception = assertThrows(PersistenceException::class.java) {
            runBlockingTest { FirstPartyEndpoint.register() }
        }

        assertEquals("Failed to save identity key", exception.message)
        assertTrue(exception.cause is KeyStoreBackendException)
        assertEquals(savingException, exception.cause!!.cause)
    }

    @Test
    fun register_failedDueToCertStore(): Unit = runBlockingTest {
        whenever(gatewayClient.registerEndpoint(any())).thenReturn(
            PrivateNodeRegistration(
                PDACertPath.PRIVATE_ENDPOINT,
                PDACertPath.PRIVATE_GW
            )
        )
        val savingException = Exception("Oh noes")
        setAwalaContext(
            Awala.getContextOrThrow().copy(
                certificateStore = MockCertificateStore(savingException = savingException)
            )
        )

        val exception = assertThrows(PersistenceException::class.java) {
            runBlockingTest { FirstPartyEndpoint.register() }
        }

        assertEquals("Failed to save certificate", exception.message)
        assertTrue(exception.cause is KeyStoreBackendException)
        assertEquals(savingException, exception.cause!!.cause)
    }

    @Test
    fun load_withResult(): Unit = runBlockingTest {
        createFirstPartyEndpoint()

        val privateAddress = KeyPairSet.PRIVATE_ENDPOINT.public.privateAddress
        with(FirstPartyEndpoint.load(privateAddress)) {
            assertNotNull(this)
            assertEquals(KeyPairSet.PRIVATE_ENDPOINT.private, this?.identityPrivateKey)
            assertEquals(PDACertPath.PRIVATE_ENDPOINT, this?.identityCertificate)
            assertEquals(listOf(PDACertPath.PRIVATE_GW), this?.identityCertificateChain)
        }
    }

    @Test
    fun load_withMissingPrivateKey() = runBlockingTest {
        whenever(storage.gatewayPrivateAddress.get())
            .thenReturn(PDACertPath.PRIVATE_GW.subjectPrivateAddress)

        assertNull(FirstPartyEndpoint.load("non-existent"))
    }

    @Test
    fun load_withKeystoreError(): Unit = runBlockingTest {
        setAwalaContext(
            Awala.getContextOrThrow().copy(
                privateKeyStore = MockPrivateKeyStore(retrievalException = Exception("Oh noes"))
            )
        )
        whenever(storage.gatewayPrivateAddress.get())
            .thenReturn(PDACertPath.PRIVATE_GW.subjectPrivateAddress)

        val exception = assertThrows(PersistenceException::class.java) {
            runBlockingTest {
                FirstPartyEndpoint.load(KeyPairSet.PRIVATE_ENDPOINT.public.privateAddress)
            }
        }

        assertEquals("Failed to load private key of endpoint", exception.message)
        assertTrue(exception.cause is KeyStoreBackendException)
    }

    @Test
    fun load_withMissingGatewayPrivateAddress(): Unit = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        whenever(storage.gatewayPrivateAddress.get(firstPartyEndpoint.privateAddress))
            .thenReturn(null)

        val exception = assertThrows(PersistenceException::class.java) {
            runBlockingTest {
                FirstPartyEndpoint.load(firstPartyEndpoint.privateAddress)
            }
        }

        assertEquals("Failed to load gateway address for endpoint", exception.message)
    }

    @Test
    fun load_withCertStoreError(): Unit = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val retrievalException = Exception("Oh noes")
        setAwalaContext(
            Awala.getContextOrThrow().copy(
                certificateStore = MockCertificateStore(retrievalException = retrievalException)
            )
        )

        val exception = assertThrows(PersistenceException::class.java) {
            runBlockingTest {
                FirstPartyEndpoint.load(firstPartyEndpoint.privateAddress)
            }
        }

        assertEquals("Failed to load certificate for endpoint", exception.message)
        assertEquals(retrievalException, exception.cause?.cause)
    }

    @Test
    fun issueAuthorization_thirdPartyEndpoint() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val thirdPartyEndpoint = ThirdPartyEndpointFactory.buildPublic()
        val expiryDate = ZonedDateTime.now().plusDays(1)

        val authorization = firstPartyEndpoint.issueAuthorization(thirdPartyEndpoint, expiryDate)

        validateAuthorization(authorization, firstPartyEndpoint, expiryDate)
    }

    @Test
    fun issueAuthorization_publicKey_valid() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val expiryDate = ZonedDateTime.now().plusDays(1)

        val authorization = firstPartyEndpoint.issueAuthorization(
            KeyPairSet.PDA_GRANTEE.public.encoded,
            expiryDate
        )

        validateAuthorization(authorization, firstPartyEndpoint, expiryDate)
    }

    @Test
    fun issueAuthorization_publicKey_invalid() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val expiryDate = ZonedDateTime.now().plusDays(1)

        val exception = assertThrows(AuthorizationIssuanceException::class.java) {
            firstPartyEndpoint.issueAuthorization(
                "This is not a key".toByteArray(),
                expiryDate
            )
        }

        assertEquals("PDA grantee public key is not a valid RSA public key", exception.message)
    }

    @Test
    fun authorizeIndefinitely_thirdPartyEndpoint() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val thirdPartyEndpoint = ThirdPartyEndpointFactory.buildPublic()
        val expiryDate = ZonedDateTime.now().plusDays(1)

        val authorization = firstPartyEndpoint.authorizeIndefinitely(thirdPartyEndpoint)

        validateAuthorization(authorization, firstPartyEndpoint, expiryDate)
        verify(channelManager).create(firstPartyEndpoint, thirdPartyEndpoint.identityKey)
    }

    @Test
    fun authorizeIndefinitely_publicKey_valid() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val expiryDate = ZonedDateTime.now().plusDays(1)

        val authorization = firstPartyEndpoint.authorizeIndefinitely(
            KeyPairSet.PDA_GRANTEE.public.encoded,
        )

        validateAuthorization(authorization, firstPartyEndpoint, expiryDate)
        verify(channelManager).create(
            eq(firstPartyEndpoint),
            argThat<PublicKey> {
                encoded.asList() == KeyPairSet.PDA_GRANTEE.public.encoded.asList()
            }
        )
    }

    @Test
    fun authorizeIndefinitely_publicKey_invalid() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()

        val exception = assertThrows(AuthorizationIssuanceException::class.java) {
            runBlocking {
                firstPartyEndpoint.authorizeIndefinitely(
                    "This is not a key".toByteArray()
                )
            }
        }

        assertEquals("PDA grantee public key is not a valid RSA public key", exception.message)
        verify(channelManager, never()).create(any(), any<PublicKey>())
    }

    @Test
    fun reissuePDAs_with_no_channel() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        whenever(channelManager.getLinkedEndpointAddresses(firstPartyEndpoint))
            .thenReturn(emptySet())

        firstPartyEndpoint.reissuePDAs()

        verify(gatewayClient, never()).sendMessage(any())
    }

    @Test
    fun reissuePDAs_with_missing_third_party_endpoint() = runBlockingTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val missingAddress = "non existing address"
        whenever(channelManager.getLinkedEndpointAddresses(firstPartyEndpoint))
            .thenReturn(setOf(missingAddress))
        val logCaptor = LogCaptor.forClass(FirstPartyEndpoint::class.java)

        firstPartyEndpoint.reissuePDAs()

        verify(gatewayClient, never()).sendMessage(any())
        assertTrue(
            logCaptor.infoLogs.contains("Ignoring missing third-party endpoint $missingAddress")
        )
    }

    @Test
    fun reissuePDAs_with_existing_third_party_endpoint() = runBlockingTest {
        val channel = createEndpointChannel(RecipientAddressType.PRIVATE)
        val firstPartyEndpoint = channel.firstPartyEndpoint

        firstPartyEndpoint.reissuePDAs()

        argumentCaptor<OutgoingMessage>().apply {
            verify(gatewayClient, times(1)).sendMessage(capture())

            val outgoingMessage = firstValue
            // Verify the parcel
            assertEquals(firstPartyEndpoint, outgoingMessage.senderEndpoint)
            assertEquals(
                channel.thirdPartyEndpoint.privateAddress,
                outgoingMessage.recipientEndpoint.privateAddress
            )
            // Verify the PDA
            val (serviceMessage) =
                outgoingMessage.parcel.unwrapPayload(channel.thirdPartySessionKeyPair.privateKey)
            assertEquals("application/vnd+relaycorp.awala.pda-path", serviceMessage.type)
            val pdaPath = CertificationPath.deserialize(serviceMessage.content)
            pdaPath.validate()
            assertEquals(
                channel.thirdPartyEndpoint.identityKey,
                pdaPath.leafCertificate.subjectPublicKey
            )
            assertEquals(firstPartyEndpoint.pdaChain, pdaPath.certificateAuthorities)
            assertEquals(pdaPath.leafCertificate.expiryDate, outgoingMessage.parcelExpiryDate)
        }
    }

    @Test
    fun delete() = runBlockingTest {
        val channel = createEndpointChannel(RecipientAddressType.PRIVATE)
        val endpoint = channel.firstPartyEndpoint

        endpoint.delete()

        assertEquals(0, privateKeyStore.identityKeys.size)
        assertEquals(0, certificateStore.certificationPaths.size)
        verify(channelManager).delete(endpoint)
    }
}

private fun validateAuthorization(
    authorizationSerialized: ByteArray,
    firstPartyEndpoint: FirstPartyEndpoint,
    expiryDate: ZonedDateTime
) {
    val authorization = CertificationPath.deserialize(authorizationSerialized)
    // PDA
    val pda = authorization.leafCertificate
    assertEquals(
        KeyPairSet.PDA_GRANTEE.public.encoded.asList(),
        pda.subjectPublicKey.encoded.asList()
    )
    assertEquals(
        2,
        pda.getCertificationPath(emptyList(), listOf(PDACertPath.PRIVATE_ENDPOINT)).size
    )
    assertSameDateTime(
        expiryDate,
        pda.expiryDate
    )

    // PDA chain
    assertEquals(firstPartyEndpoint.pdaChain, authorization.certificateAuthorities)
}
