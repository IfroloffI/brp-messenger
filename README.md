# BRP Messenger

мессенджер на Java для локальной сети: узлы находят друг друга по **UDP**, строят **кольцевую топологию** и пересылают сообщения по **TCP**. Поддерживаются адресная доставка (`/to`) и рассылка всем (`/all`).

## Протоколы и механизмы

| Слой | Порт | Протокол | Назначение |
|------|------|----------|------------|
| Обнаружение | **9876** | **UDP** (broadcast) | Периодические маяки с `nodeId` и IP; таблица «живых» узлов; таймаут неактивных. |
| Транспорт кольца | **9877** | **TCP** | Входящее соединение слева, исходящее к правому соседу; после connect передаётся `long` (ID узла). Сообщения сериализуются в поток. |

По снимку живых узлов (`nodeId` → IP) пересчитываются левый и правый сосед в кольце. Сообщение идёт вправо, пока не достигнет адресата или не будет обработано как broadcast. При недоступности правого соседа сообщения могут сохраняться в **очередь (MapDB)** и уйти после восстановления соединения.

## Требования

- Windows (ниже — пример для PowerShell)
- **JDK 17** (рекомендуется Eclipse Temurin)
- **Apache Maven 3.9.x**

## Установка окружения (PowerShell)

### JDK 17

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```

После установки перезапустите терминал. Проверка: `java -version`.

### Apache Maven

Скачайте архив, например:  
https://dlcdn.apache.org/maven/maven-3/3.9.15/binaries/apache-maven-3.9.15-bin.zip  

Распакуйте (например, в `C:\Tools\apache-maven-3.9.15`), добавьте `...\bin` в **PATH**. Проверка: `mvn -version`. При необходимости задайте `JAVA_HOME` на JDK 17.

### Профиль сети: Private

На Windows для нормальной работы broadcast удобно выставить сети категорию **Private**:

```powershell
Get-NetConnectionProfile
```

```powershell
Set-NetConnectionProfile -InterfaceIndex 4 -NetworkCategory Private
```

Подставьте свой `InterfaceIndex` вместо `4`.

### Брандмауэр

На каждом ПК, где запускается мессенджер:

```powershell
New-NetFirewallRule -DisplayName "BRP Messenger TCP 9877" -Direction Inbound -Protocol TCP -LocalPort 9877 -Action Allow

New-NetFirewallRule -DisplayName "BRP Messenger discovery UDP 9876" `
  -Direction Inbound -Action Allow -Protocol UDP -LocalPort 9876 -Profile Private
```

## Сборка и запуск

Из корня репозитория (где лежит `pom.xml`).

**Первый запуск или после изменений в коде:**

```powershell
mvn compile exec:java
```

**Если пересборка не нужна:**

```powershell
mvn exec:java
```

В `pom.xml` задан `defaultGoal` `compile exec:java`, поэтому достаточно:

```powershell
mvn
```

Точка входа: `messenger.app.Main` (настроена в `exec-maven-plugin`, `-Dexec.mainClass` не обязателен).

## Пользование

В консоли отображаются локальный IP, назначенный `nodeId` и строка вида:

`Ring ready: myId=..., left=..., right=...`

При изменении топологии эти данные могут обновляться.

| Команда | Описание |
|---------|----------|
| `/all <текст>` | Сообщение всем узлам |
| `/to <id> <текст>` | Сообщение узлу с указанным id |
| `/exit` | Выход |

Логи пишутся в файл (путь выводится при старте; по умолчанию каталог `%USERPROFILE%\.brp-messenger\`).

## Устранение проблем

- **Узлы не видят друг друга:** профиль сети Private, входящий **UDP 9876**, одна подсеть, на Wi‑Fi нет изоляции клиентов.
- **Сообщения не уходят, `right=null`:** входящий **TCP 9877** на соседе, мессенджер запущен, нет блокировки антивирусом.
- **Коллизии id:** предусмотрено переназначение при конфликте; id может храниться в файле в профиле пользователя.
