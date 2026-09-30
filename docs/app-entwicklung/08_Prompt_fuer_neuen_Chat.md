# 08 – Texte für einen neuen Chat

Einen der Texte unten in einen neuen Chat einfügen.

- **Claude Code mit dem Repo** (empfohlen): Repo `MADTreasures/ER-G2-custom` wählen. Der Standard-Branch
  enthält alles (Uhr-App, Baukasten, diese Mappe). Es müssen **keine Dateien** hochgeladen werden.
- **Chat ohne Repo-Zugriff:** die Projekt-ZIP (`G2Watch-Projekt.zip`, das ganze Repo) hochladen und im
  Text „Repo“ durch „die hochgeladene ZIP“ ersetzen. Für reine Fragen ohne Code reicht `app-entwicklung.zip`.

## Start: die erste App auf der Brille

Solange es den App-Host auf der Uhr noch nicht gibt (Stand-Tabelle in [00](00_LIES_MICH.md)), muss der
erste Chat ihn bauen. Dieser Text macht das und liefert gleich zwei Beispiel-Apps:

```
Repo: MADTreasures/ER-G2-custom (Standard-Branch).

Ich baue Apps für meine Even Realities G2 mit Faceclaw-Firmware (Revision 35). Die Brille wird von
meiner Pixel Watch 5 (45 mm, LTE, Wear OS 7) über die Uhr-App „G2 Watch“ (Ordner app/) gesteuert;
die verbindet sich schon per Bluetooth und zeigt einen Maus-Desktop auf der Brille.

Lies zuerst README.md, docs/app-entwicklung/00_LIES_MICH.md bis 07_Umsetzungsplan.md und
designer/README.md.

Auftrag: Setze Meilenstein M1 aus docs/app-entwicklung/07_Umsetzungsplan.md um – den App-Host auf der
Uhr nach 03 §5 (Starter, App-Menü, InputRouter nach 03 §5.1, Gesten-Modus, Anschlüsse für
EvenHub-Sitzungen nach 03 §5.2) mit den Beispiel-Apps Stoppuhr und Einkaufsliste.

Regeln: Tests ohne Hardware (simulierte Brille, Fakes, Snapshot-Bilder), CI und Lint grün, Doku und
Bilder nachziehen, Versionsnummer erhöhen. Nichts als auf Hardware erprobt bezeichnen. Evens Firmware
nie ins Repo, Firmware-Pfad nicht anfassen (FlashingBoundaryTest muss grün bleiben). Code und Commits
auf Englisch, Erklärungen für mich auf Deutsch.

Am Ende: Erklär mir kurz, was gebaut ist, wie ich es mit Android Studio auf die Uhr bringe und was ich
auf der echten Brille ausprobieren soll, und gib mir eine ZIP des Projekts für Android Studio.
```

## Jede weitere App

```
Repo: MADTreasures/ER-G2-custom (Standard-Branch). Lies docs/app-entwicklung/00–03 und
designer/README.md (bei Rechner-Apps zusätzlich 04, bei Even-Hub-Apps 05).

Baue mir eine App für die Brille nach docs/app-entwicklung/02 und 03.
Name: <Name>
Sie soll: <was sie tut, 3–5 Sätze; was bei welchem Knopf passiert>
Seiten: <JSON aus dem G2 Baukasten einfügen („⋯“ → „Für den Chat kopieren“)>

Falls ein Plattform-Teil fehlt (Stand-Tabelle in 00), sag mir das zuerst und schlag vor, welchen
Meilenstein aus 07 wir vorziehen. Liefere: Code unter app/src/main/java/ch/madtreasures/g2watch/apps/builtin/<name>/,
die Seiten als Asset (apps/<app-id>/ui.json), den Eintrag in AppRegistry, Tests mit FakeAppContext,
Snapshot-Bilder, eine kurze Anleitung und eine ZIP für Android Studio. Tests ohne Hardware, CI und Lint
grün, Code und Commits Englisch, Erklärungen Deutsch.
```

## A. Allgemein: eine App bauen

```
Ich entwickle Apps für meine Even Realities G2 mit Faceclaw-Firmware (Revision 35). Die Brille wird
von meiner Pixel Watch 5 (Wear OS 7, LTE) über die App „G2 Watch“ gesteuert. Eigene Apps laufen auf der
Uhr (Kotlin) oder, wenn sie viel rechnen, auf meinem PC im Programm „g2-host“ (über WLAN/LTE).
Even-Hub-Apps laufen auf der Uhr in GeckoView, sonst auf dem Handy in der App „G2 Handy“ – nicht auf dem PC.

Lies zuerst docs/app-entwicklung/00_LIES_MICH.md bis 07_Umsetzungsplan.md (Repo MADTreasures/ER-G2-custom).
Halte dich an das App-Modell (02) und an die harten Regeln aus 00. Code und Commits auf Englisch,
Erklärungen für mich auf Deutsch.

Meine App: <Name>. Sie soll: <was sie tut, in 3–5 Sätzen>. Laufzeit: <Uhr / Rechner / Even-Hub-App /
weiß nicht – entscheide nach 00 und begründe>. Die Seiten habe ich im G2 Baukasten entworfen: <JSON
einfügen oder „schau im Baukasten nach“>.

Liefere: Manifest, Seiten (ui.json), Code, Tests, eine kurze Anleitung zum Installieren. Wenn dafür
ein Plattform-Teil fehlt (siehe Stand-Tabelle in 00), sag mir das zuerst und schlage vor, welchen
Meilenstein aus 07 wir vorziehen.
```

## B. Plattform-Meilensteine

Für jeden Meilenstein denselben Rahmen verwenden und nur die Zeile „Auftrag“ austauschen:

```
Repo: MADTreasures/ER-G2-custom. Lies docs/app-entwicklung/00–07 und README.md. Arbeite auf einem
eigenen Branch, teste ohne Hardware (simulierte Brille, Fakes, Snapshot-Bilder), halte CI und Lint grün,
zieh die Doku nach. Nichts als auf Hardware erprobt bezeichnen. Code und Commits Englisch, Erklärungen
Deutsch. Evens Firmware nie ins Repo, kein Zugriff auf Evens Store-Server, Firmware-Pfad nicht anfassen.

Auftrag: <eine Zeile aus der Liste unten>
```

| Meilenstein | Auftrag |
|---|---|
| M0 | Setze M0 aus docs/app-entwicklung/07_Umsetzungsplan.md um (Baukasten an die App-Fläche anpassen, Kennungen, Bild-Baustein, Knopf „Zurück“) und veröffentliche den Baukasten unter derselben Adresse neu. |
| M1 | Setze M1 um: App-Host auf der Uhr nach 03 §5, Starter, App-Menü, Stoppuhr und Einkaufsliste, Gesten-Modus, Tests und Bilder. |
| M2 | Setze M2 um: eine Test-APK mit GeckoView für meine Pixel Watch 5 nach 05 §5.1. Sag mir genau, wie ich sie installiere und welche Werte ich dir zurückmelde. |
| M3 | Setze M3 um: die EvenHub-Laufzeit auf der Uhr nach 05 §4–§5 mit Test-App für alle Methoden (Mikrofon/IMU/Standort erst M6). |
| M4 | Setze M4 um: die Handy-App „G2 Handy“ nach 05 §6 und 06 §1 (Ausweich-Engine, Apps installieren, Datenschicht). |
| M5 | Setze M5 um: Protokoll g2-remote@1 mit Test-Vektoren, g2-host in host/ mit Web-Seite, Rechner-Client und Kopplung auf der Uhr, Beispiel-Apps echo, pc-status, notizen. |
| M6 | Setze M6 um: Sensoren, Mikrofon (LC3), Summer und Standort bis zu den Apps, Beispiele „Diktat“ (Rechner) und „Kompass“ (Uhr). |
| M7 | Setze M7 um: den Web-Browser auf der Brille mit GeckoView nach 05 §10. |

## C. Einen Entwurf aus dem Baukasten übergeben

```
Hier ist mein Entwurf aus dem G2 Baukasten (g2-baukasten@1): <JSON>
Mach daraus eine <Uhr-App / Rechner-App> nach docs/app-entwicklung/02 und 03 bzw. 04.
In den Notizen der Seiten steht, was passieren soll.
```

In claude.ai kann Claude den Entwurf auch direkt aus dem Baukasten lesen („Schau dir meinen Baukasten an“).
