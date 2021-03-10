package tech.relaycorp.relaydroid.storage

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock

internal fun mockStorage() = mock<StorageImpl> {
    on { identityKeyPair } doReturn mock()
    on { identityCertificate } doReturn mock()
    on { gatewayCertificate } doReturn mock()
    on { publicThirdPartyCertificate } doReturn mock()
    on { thirdPartyAuthorization } doReturn mock()
    on { thirdPartyIdentityCertificate } doReturn mock()
}