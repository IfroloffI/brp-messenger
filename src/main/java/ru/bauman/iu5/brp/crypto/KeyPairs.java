package ru.bauman.iu5.brp.crypto;

import java.security.KeyPair;

/**
 * Контейнер для двух пар ключей: шифрование + подпись
 */
public record KeyPairs(
        KeyPair encryptionKeyPair,
        KeyPair signingKeyPair
) {
}
