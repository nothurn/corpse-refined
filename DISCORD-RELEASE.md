# Automatische Discord-Releases

Der explizite Gradle-Task `publishDiscordRelease` erledigt in einem Durchlauf:

1. sauberen Gradle-Build;
2. Ermittlung der Version aus `gradle.properties`;
3. Auswahl des passenden Abschnitts aus `CHANGELOG.md`;
4. Upload der JAR samt Kurzfassung nach `📦releases`;
5. Veröffentlichung des vollständigen aktuellen Changelogs nach `🧾changelog`.

Ein normaler `build` veröffentlicht niemals etwas.

## Einmalige Einrichtung

In Discord jeweils **Kanal bearbeiten → Integrationen → Webhooks → Neuer Webhook** öffnen:

- einen Webhook für `📦releases` erstellen;
- einen zweiten Webhook für `🧾changelog` erstellen.

Die beiden URLs sind geheime Zugangsdaten. Niemals in Chats, Commits, Screenshots oder Logs posten.

Am einfachsten `Discord-Release.cmd` doppelklicken und **Option 1** wählen. Die Einrichtung startet
PowerShell direkt, damit die verdeckten Eingaben zuverlässig angenommen werden. Alternativ im
korrekt eingerichteten Terminal:

```powershell
.\gradlew.bat configureDiscordRelease
```

Die Eingabe bleibt unsichtbar. Die URLs werden lokal mit AES-256 verschlüsselt in
`.discord-release-secrets.json` gespeichert. Der zufällig erzeugte Schlüssel liegt getrennt in
`.discord-release-key.bin`; Windows-Dateirechte beschränken beide Dateien auf den aktuellen Nutzer.
Beide Dateien sind zusätzlich in `.gitignore` eingetragen und dürfen niemals weitergegeben werden.

Die optionale Release-Ping-Rollen-ID für Craftbound lautet aktuell:

```text
1543588214101778513
```

Das Feld kann leer bleiben, wenn Releases ohne Rollen-Ping erscheinen sollen.

## Vorabprüfung ohne Discord

Im Doppelklick-Starter **Option 2** wählen oder im Terminal:

```powershell
.\gradlew.bat validateDiscordRelease --no-daemon
```

Der Task baut das Projekt und zeigt exakt die geplante Release- und Changelog-Ausgabe an. Es wird
nichts an Discord gesendet.

## Veröffentlichung

Vorher `mod_version` und den gleichnamigen Abschnitt `## VERSION` in `CHANGELOG.md` aktualisieren.
Danach am einfachsten die Desktop-Verknüpfung **Craftbound Release** doppelklicken und die einmalige
Ja/Nein-Abfrage bestätigen. Sie startet `Publish-Craftbound-Release.cmd`, führt alle Prüfungen aus,
baut die JAR und veröffentlicht beide Discord-Beiträge. Alternativ im bisherigen Starter **Option 3**
wählen und mit `PUBLISH` bestätigen oder im Terminal:

```powershell
.\gradlew.bat publishDiscordRelease --no-daemon
```

Der Task führt einen sauberen Build aus und veröffentlicht anschließend beide Beiträge. Pro Version
und JAR-Hash wird lokal gespeichert, was bereits veröffentlicht wurde. Ein versehentlicher zweiter
Aufruf erzeugt daher keine doppelten Beiträge. Wenn nur der Changelog-Versand fehlschlug, setzt der
nächste Aufruf an der fehlenden Stelle fort.

`--no-daemon` ist hier absichtlich gesetzt: Der einmalige Gradle-Prozess läuft unter dem gerade
angemeldeten Windows-Konto und kann dadurch auf dessen geschützte Webhook-Konfiguration zugreifen.

Die Verknüpfung ist bewusst nicht an einen normalen Test-Build gekoppelt. So kann ein versehentlicher
Build aus der Entwicklungsumgebung niemals ungefragt eine öffentliche Discord-Nachricht erzeugen.

Eine bereits veröffentlichte Versionsnummer darf nicht still mit einer anderen JAR oder einem anderen
Changelog überschrieben werden. In diesem Fall verlangt das Tool einen Versionssprung. `-Force` ist
nur für eine bewusst gewünschte vollständige Neuveröffentlichung vorgesehen und wird nicht vom
normalen Gradle-Task gesetzt.

## Wenn eine Webhook-URL bekannt wird

Den betroffenen Webhook sofort in Discord löschen, neu erstellen und danach
`configureDiscordRelease` erneut ausführen.
