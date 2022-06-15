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
import kotlinx.coroutines.test.runTest
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
import tech.relaycorp.awaladroid.common.toPublicKey
import tech.relaycorp.awaladroid.messaging.OutgoingMessage
import tech.relaycorp.awaladroid.storage.persistence.PersistenceException
import tech.relaycorp.awaladroid.test.FirstPartyEndpointFactory
import tech.relaycorp.awaladroid.test.MockContextTestCase
import tech.relaycorp.awaladroid.test.ThirdPartyEndpointFactory
import tech.relaycorp.awaladroid.test.assertSameDateTime
import tech.relaycorp.awaladroid.test.setAwalaContext
import tech.relaycorp.relaynet.issueEndpointCertificate
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
    fun register() = runTest {
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

    @Test
    fun reRegister() = runTest {
        val endpoint = FirstPartyEndpointFactory.build()
        val newCertificate = issueEndpointCertificate(
            subjectPublicKey = endpoint.identityPrivateKey.toPublicKey(),
            issuerPrivateKey = KeyPairSet.PRIVATE_GW.private,
            validityEndDate = ZonedDateTime.now().plusYears(1),
        )
        whenever(gatewayClient.registerEndpoint(any())).thenReturn(
            PrivateNodeRegistration(
                newCertificate,
                PDACertPath.PRIVATE_GW
            )
        )

        endpoint.reRegister()

        val identityCertificatePath = certificateStore.retrieveLatest(
            endpoint.identityPrivateKey.privateAddress,
            PDACertPath.PRIVATE_GW.subjectPrivateAddress
        )
        assertEquals(newCertificate, identityCertificatePath!!.leafCertificate)
    }

    @Test(expected = RegistrationFailedException::class)
    fun register_failed() = runTest {
        whenever(gatewayClient.registerEndpoint(any())).thenThrow(RegistrationFailedException(""))

        FirstPartyEndpoint.register()

        verifyZeroInteractions(storage)
        assertEquals(0, privateKeyStore.identityKeys.size)
    }

    @Test(expected = GatewayProtocolException::class)
    fun register_failedDueToProtocol(): Unit = runTest {
        whenever(gatewayClient.registerEndpoint(any())).thenThrow(GatewayProtocolException(""))

        FirstPartyEndpoint.register()

        verifyZeroInteractions(storage)
        assertEquals(0, privateKeyStore.identityKeys.size)
    }

    @Test
    fun register_failedDueToPrivateKeystore(): Unit = runTest {
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

        try {
            FirstPartyEndpoint.register()
        } catch (exception: PersistenceException) {
            assertEquals("Failed to save identity key", exception.message)
            assertTrue(exception.cause is KeyStoreBackendException)
            assertEquals(savingException, exception.cause!!.cause)
            return@runTest
        }

        assert(false)
    }

    @Test
    fun register_failedDueToCertStore(): Unit = runTest {
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

        try {
            FirstPartyEndpoint.register()
        } catch (exception: PersistenceException) {
            assertEquals("Failed to save certificate", exception.message)
            assertTrue(exception.cause is KeyStoreBackendException)
            assertEquals(savingException, exception.cause!!.cause)
            return@runTest
        }

        assert(false)
    }

    @Test
    fun load_withResult(): Unit = runTest {
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
    fun load_withMissingPrivateKey() = runTest {
        whenever(storage.gatewayPrivateAddress.get())
            .thenReturn(PDACertPath.PRIVATE_GW.subjectPrivateAddress)

        assertNull(FirstPartyEndpoint.load("non-existent"))
    }

    @Test
    fun load_withKeystoreError(): Unit = runTest {
        setAwalaContext(
            Awala.getContextOrThrow().copy(
                privateKeyStore = MockPrivateKeyStore(retrievalException = Exception("Oh noes"))
            )
        )
        whenever(storage.gatewayPrivateAddress.get())
            .thenReturn(PDACertPath.PRIVATE_GW.subjectPrivateAddress)

        try {
            FirstPartyEndpoint.load(KeyPairSet.PRIVATE_ENDPOINT.public.privateAddress)
        } catch (exception: PersistenceException) {
            assertEquals("Failed to load private key of endpoint", exception.message)
            assertTrue(exception.cause is KeyStoreBackendException)
            return@runTest
        }

        assert(false)
    }

    @Test
    fun load_withMissingGatewayPrivateAddress(): Unit = runTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        whenever(storage.gatewayPrivateAddress.get(firstPartyEndpoint.privateAddress))
            .thenReturn(null)

        try {
            FirstPartyEndpoint.load(firstPartyEndpoint.privateAddress)
        } catch (exception: PersistenceException) {
            assertEquals("Failed to load gateway address for endpoint", exception.message)
            return@runTest
        }

        assert(false)
    }

    @Test
    fun load_withCertStoreError(): Unit = runTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val retrievalException = Exception("Oh noes")
        setAwalaContext(
            Awala.getContextOrThrow().copy(
                certificateStore = MockCertificateStore(retrievalException = retrievalException)
            )
        )

        try {
            FirstPartyEndpoint.load(firstPartyEndpoint.privateAddress)
        } catch (exception: PersistenceException) {
            assertEquals("Failed to load certificate for endpoint", exception.message)
            assertEquals(retrievalException, exception.cause?.cause)
            return@runTest
        }
    }

    @Test
    fun issueAuthorization_thirdPartyEndpoint() = runTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val thirdPartyEndpoint = ThirdPartyEndpointFactory.buildPublic()
        val expiryDate = ZonedDateTime.now().plusDays(1)

        val authorization = firstPartyEndpoint.issueAuthorization(thirdPartyEndpoint, expiryDate)

        validateAuthorization(authorization, firstPartyEndpoint, expiryDate)
    }

    @Test
    fun issueAuthorization_publicKey_valid() = runTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val expiryDate = ZonedDateTime.now().plusDays(1)

        val authorization = firstPartyEndpoint.issueAuthorization(
            KeyPairSet.PDA_GRANTEE.public.encoded,
            expiryDate
        )

        validateAuthorization(authorization, firstPartyEndpoint, expiryDate)
    }

    @Test
    fun issueAuthorization_publicKey_invalid() = runTest {
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
    fun authorizeIndefinitely_thirdPartyEndpoint() = runTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        val thirdPartyEndpoint = ThirdPartyEndpointFactory.buildPublic()
        val expiryDate = ZonedDateTime.now().plusDays(1)

        val authorization = firstPartyEndpoint.authorizeIndefinitely(thirdPartyEndpoint)

        validateAuthorization(authorization, firstPartyEndpoint, expiryDate)
        verify(channelManager).create(firstPartyEndpoint, thirdPartyEndpoint.identityKey)
    }

    @Test
    fun authorizeIndefinitely_publicKey_valid() = runTest {
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
    fun authorizeIndefinitely_publicKey_invalid() = runTest {
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
    fun reissuePDAs_with_no_channel() = runTest {
        val firstPartyEndpoint = createFirstPartyEndpoint()
        whenever(channelManager.getLinkedEndpointAddresses(firstPartyEndpoint))
            .thenReturn(emptySet())

        firstPartyEndpoint.reissuePDAs()

        verify(gatewayClient, never()).sendMessage(any())
    }

    @Test
    fun reissuePDAs_with_missing_third_party_endpoint() = runTest {
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
    fun reissuePDAs_with_existing_third_party_endpoint() = runTest {
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
    fun delete() = runTest {
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
