## VM Options:

```VMOptions
--module-path "A:\javafx-sdk-21.0.10\lib" 
--add-modules javafx.controls,javafx.fxml 
--enable-native-access=ALL-UNNAMED,javafx.graphics 
--sun-misc-unsafe-memory-access=allow
```

# Паттерны:
- Singleton — BRPService, Config
- Observer — NetworkEvent → UI обновления  
- Factory — FrameFactory (I/Link/Uplink/ACK/Ret)
- Strategy — CryptoStrategy (AES/ECDSA)
- Command — MessageCommand
- State — RingState (CONNECTED/DISCONNECTED)
- Builder — FrameBuilder

# Слои:
### TODO: перепромтить доку
- UI (MainWindow)
- [api/] TransportApi/LinkApi/ApplicationApi ← Интерфейсы
- UseCase (ApplicationService) ← Оркестрация
- link.LinkLayer → transport.NioTransportLayer ← Реализации
