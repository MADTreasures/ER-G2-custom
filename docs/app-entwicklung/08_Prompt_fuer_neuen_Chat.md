# 08 – Texte für einen neuen Chat

Einen der Texte unten in einen neuen Chat einfügen. In **Claude Code** mit diesem Repo hat der Chat alle
Dateien selbst. In einem **normalen Chat** zusätzlich die ZIP dieser Mappe (`app-entwicklung.zip`) und bei
Bedarf die Projekt-ZIP hochladen.

## A. Allgemein: eine App bauen

```
Ich entwickle Apps für meine Even Realities G2 mit Faceclaw-Firmware (Revision 35). Die Brille wird
von meiner Pixel Watch 5 (Wear OS 7, LTE) über die App „G2 Watch“ gesteuert. Rechenintensive Apps
laufen auf meinem PC im Programm „g2-host“ und sprechen über WLAN/LTE mit der Uhr.

Lies zuerst docs/app-entwicklung/00_LIES_MICH.md bis 07_Umsetzungsplan.md (Repo MADTreasures/ER-G2-custom).
Halte dich an das App-Modell (02) und an die harten Regeln aus 00. Code und Commits auf Englisch,
Erklärungen für mich auf Deutsch.

Meine App: <Name>. Sie soll: <was sie tut, in 3–5 Sätzen>. Laufzeit: <Uhr / Rechner / weiß nicht –
entscheide nach 00 und begründe>. Die Seiten habe ich im G2 Baukasten entworfen: <JSON einfügen oder
„schau im Baukasten nach“>.

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
| M1 | Setze M1 um: App-Host auf der Uhr nach 03 §5, Starter, Stoppuhr und Einkaufsliste, Gesten-Modus, Tests und Bilder. |
| M2 | Setze M2 um: Protokoll g2-remote@1 mit Test-Vektoren, g2-host in host/ (Node 22, TypeScript), Rechner-Client und Kopplung auf der Uhr, Beispiel-Apps echo, pc-status, notizen. |
| M3 | Setze M3 um: die App-Verwaltung nach 06 mit allen Installationswegen und app.launch. |
| M4 | Setze M4 um: den EvenHub-Adapter nach 05 §3 mit Test-App für alle 16 Methoden. |
| M5 | Setze M5 um: Sensoren, Mikrofon und Summer bis zu den Apps, Beispiele „Diktat“ (Rechner) und „Kompass“ (Uhr). |

## C. Einen Entwurf aus dem Baukasten übergeben

```
Hier ist mein Entwurf aus dem G2 Baukasten (g2-baukasten@1): <JSON>
Mach daraus eine <Uhr-App / Rechner-App> nach docs/app-entwicklung/02 und 03 bzw. 04.
In den Notizen der Seiten steht, was passieren soll.
```

In claude.ai kann Claude den Entwurf auch direkt aus dem Baukasten lesen („Schau dir meinen Baukasten an“).
