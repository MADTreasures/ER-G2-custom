# 08 – Texte für neue Chats

Es gibt zwei Arten von Chats ([09 §1](09_App-Pakete.md#1-wer-macht-was)):

- **App-Chat:** baut genau eine App als App-Paket. Er ändert nur ihren Ordner `packages/<name>/` und liefert
  die Paket-Datei (`.g2app`, eine ZIP-Datei), die du auf die Uhr legst und dort installierst. App-Chats
  kommen sich darum nicht in die Quere; es dürfen mehrere gleichzeitig laufen.
- **Hauptchat:** ändert die Uhr-App selbst (App-Host, Schnittstelle, Installation, Firmware). Nur einer auf
  einmal. Braucht eine App etwas Neues von der Uhr-App, sagt der App-Chat es dir, und du gibst es an den
  Hauptchat weiter.

## So geht's

1. Neuen Chat in **Claude Code** öffnen und das Repo `MADTreasures/ER-G2-custom` wählen. Es müssen
   **keine Dateien** hochgeladen werden: Der Standard-Branch enthält alles (Uhr-App, Baukasten, diese Mappe).
   Ohne Repo-Zugriff: die Projekt-ZIP (`G2Watch-Projekt.zip`) hochladen.
2. Den passenden Text unten einfügen und nach **„Meine App:“** bzw. **„Auftrag:“** in eigenen Worten
   beschreiben, was du willst. Seiten aus dem [G2 Baukasten](../../designer/README.md) („⋯“ → „Für den Chat
   kopieren“) können mit dazu, müssen aber nicht.
3. Am Ende gibt dir ein App-Chat die Paket-Datei: auf die Uhr legen und unter **Einstellungen → Apps
   installieren** installieren ([09 §4](09_App-Pakete.md#4-auf-die-uhr-bringen-und-installieren)). Den Pull
   Request des Chats auf GitHub zusammenführen („Merge“), damit der Quellcode im Repo bleibt.

## App-Chat: eine App bauen

```
Repo: MADTreasures/ER-G2-custom (Standard-Branch; ohne Repo-Zugriff die hochgeladene ZIP).

Ich baue Apps für meine Even Realities G2 mit Faceclaw-Firmware (Revision 35). Die Brille wird von
meiner Pixel Watch 5 (45 mm, LTE, Wear OS 7) über die Uhr-App „G2 Watch“ gesteuert. Eigene Apps sind
App-Pakete (.g2app), die ich getrennt von der Uhr-App auf der Uhr installiere.
Lies zuerst README.md, docs/app-entwicklung/00_LIES_MICH.md bis 03_Uhr-Apps.md, 09_App-Pakete.md und
designer/README.md; bei Rechner- oder Even-Hub-Apps auch 04 bis 07.

So gehst du vor:
1. Wähl die Laufzeit nach 00 und begründe sie in einem Satz: normalerweise ein App-Paket für die Uhr;
   eine Rechner-App nur, wenn sie viel rechnet; Even-Hub-Apps laufen auf der Uhr oder dem Handy, nie auf
   dem Rechner.
2. Bau die App als App-Paket nach 09 in packages/<name>/ (Vorlage: packages/youtube; mit Web-Seite:
   packages/browser).
   Die Uhr-App selbst (app/, app-api/, die Bau-Logik im Hauptordner) änderst du nicht.
3. Braucht die App etwas, das die Schnittstelle nicht kann, oder einen Plattform-Teil, der noch fehlt
   (Rechner = M5, Mikrofon/Sensoren = M6 usw.), sag mir das zuerst: was genau fehlt und wie groß es ist.
   Das baut dann der Hauptchat in die Uhr-App.
4. Wenn ich Seiten aus dem G2 Baukasten mitgebe, nimm sie; sonst entwirf die Seiten selbst nach 02.
5. Wo meine Beschreibung Lücken hat, triff sinnvolle Annahmen und nenn sie mir am Ende.

Regeln: Tests ohne Hardware in packages/<name>/src/test/kotlin (mit FakeAppContext), CI und Lint grün,
bei jeder Änderung die Version der App erhöhen. Nichts als auf Hardware erprobt bezeichnen. Evens Firmware
nie ins Repo, kein Zugriff auf Evens Store-Server. Code und Commits auf Englisch, Erklärungen für mich auf
Deutsch.

Am Ende: Committe, pushe und öffne einen Pull Request auf den Standard-Branch. Bau die Paket-Datei
(./gradlew :packages:<name>:g2app) und gib sie mir. Erklär mir kurz, was die App kann, wie ich sie auf die
Uhr lege und installiere (09 §4) und was ich auf der echten Brille ausprobieren soll.

Meine App:
```

## Hauptchat: die Uhr-App selbst ändern

```
Repo: MADTreasures/ER-G2-custom (Standard-Branch).

Du bist der Hauptchat für die Uhr-App „G2 Watch“ meiner Pixel Watch 5 (45 mm, LTE, Wear OS 7), die meine
Even Realities G2 mit Faceclaw-Firmware (Revision 35) steuert: App-Host, Schnittstelle app-api/,
Installation der App-Pakete, Firmware. Einzelne Apps bauen die App-Chats in packages/.
Lies zuerst README.md und docs/app-entwicklung/00_LIES_MICH.md bis 09_App-Pakete.md.

Regeln: Die Schnittstelle wächst nur (09 §5): nichts umbenennen oder entfernen, bei jeder Erweiterung
G2AppApi.VERSION erhöhen, die Pakete in packages/ bauen weiter. Prüf vorher die offenen Pull Requests,
damit nichts doppelt gebaut wird. Tests ohne Hardware (simulierte Brille, Fakes, Snapshot-Bilder), CI und
Lint grün, Doku, Stand-Tabelle in 00 und Bilder nachziehen, Versionsnummer der Uhr-App erhöhen. Nichts
als auf Hardware erprobt bezeichnen. Evens Firmware nie ins Repo, kein Zugriff auf Evens Store-Server,
Firmware-Pfad nicht anfassen (FlashingBoundaryTest bleibt grün). Code und Commits auf Englisch,
Erklärungen für mich auf Deutsch.

Am Ende: Committe, pushe und öffne einen Pull Request auf den Standard-Branch. Erklär mir kurz, was
gebaut ist, wie ich die Uhr-App mit Android Studio auf die Uhr bringe und was ich ausprobieren soll.

Auftrag:
```

Für einen Meilenstein aus dem [Umsetzungsplan](07_Umsetzungsplan.md) nach „Auftrag:“ eine dieser Zeilen:

| Meilenstein | Zeile |
|---|---|
| M0 | Setze M0 um (Baukasten an die App-Fläche anpassen, Kennungen, Bild-Baustein, Knopf „Zurück“) und veröffentliche den Baukasten unter derselben Adresse neu. |
| M2 | Der Gecko-Test (tools/gecko-probe) ist gebaut und lief auf meiner Uhr; hier ist sein Bericht: <g2-gecko-bericht.txt einfügen>. Trag die Werte in quellen/E-m2-messwerte.md ein, entscheide nach 05 §5.1, ob GeckoView auf der Uhr taugt, und zieh den schon gebauten Browser nach, was davon abhängt (05 §10.2). |
| M3 | Setze M3 um: EvenHub-Laufzeit auf der Uhr nach 05 §4–§5 mit Test-App für alle Methoden. |
| M4 | Setze M4 um: Handy-App „G2 Handy“ nach 05 §6 und 06 §1. |
| M5 | Setze M5 um: Protokoll g2-remote@1, g2-host mit Web-Seite, Rechner-Client und Kopplung, Beispiel-Apps echo, pc-status, notizen. |
| M6 | Setze M6 um: Sensoren, Mikrofon (LC3), Summer und Standort bis zu den Apps, Beispiele „Diktat“ und „Kompass“. |
| M7 | Der Browser (M7, v0.8.0) lief auf meiner Uhr; das fiel auf: <Beobachtungen, Adressen, Protokollzeilen „Browser: …“>. Behebe es nach 05 §10.2 und 03 §12. |

M1 (App-Host) und M1b (App-Pakete) sind fertig, von M2 die Test-APK (es fehlen die Messwerte der Uhr),
M7 (Browser) ist gebaut, aber nicht auf Hardware erprobt. In claude.ai kann Claude einen Entwurf auch direkt aus dem
Baukasten lesen („Schau dir meinen Baukasten an“).
