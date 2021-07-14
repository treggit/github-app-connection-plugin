package jetbrains.buildServer.teamcity.github.utils

import jetbrains.buildServer.teamcity.github.connection.exceptions.PrivateKeyContentParseException
import org.apache.commons.io.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.security.PrivateKey
import java.security.Security


object PEMUtils {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun getPrivateKey(file: File): PrivateKey {
        return getPrivateKey(FileUtils.readFileToByteArray(file))
    }

    fun getPrivateKey(content: ByteArray): PrivateKey {
        return getPrivateKey(ByteArrayInputStream(content))
    }

    fun getPrivateKey(content: ByteArrayInputStream): PrivateKey {
        try {
            val parser = PEMParser(InputStreamReader(content))
            val converter = JcaPEMKeyConverter().setProvider("BC")
            return converter.getKeyPair(parser.readObject() as PEMKeyPair).private
        } catch (th: Throwable) {
            throw PrivateKeyContentParseException(reason = th)
        }
    }
}