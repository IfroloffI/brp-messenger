package messenger.crypto;

import java.security.KeyPair;

/**
 * Контейнер для двух пар ключей: шифрование + подпись
 */
public record KeyPairs(
        KeyPair encryptionKeyPair,
        KeyPair signingKeyPair
) {
}