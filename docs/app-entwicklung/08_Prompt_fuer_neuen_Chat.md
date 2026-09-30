# 08 – Text für einen neuen Chat

## So geht's

1. Neuen Chat in **Claude Code** öffnen und das Repo `MADTreasures/ER-G2-custom` wählen. Es müssen
   **keine Dateien** hochgeladen werden: Der Standard-Branch enthält alles (Uhr-App, Baukasten, diese Mappe).
   Ohne Repo-Zugriff: die Projekt-ZIP (`G2Watch-Projekt.zip`) hochladen.
2. Den Text unten einfügen und nach **„Meine App:“** in eigenen Worten beschreiben, was die App tun soll.
   Seiten aus dem [G2 Baukasten](../../designer/README.md) („⋯“ → „Für den Chat kopieren“) können mit
   dazu, müssen aber nicht.
3. Am Ende öffnet der Chat einen **Pull Request**. Diesen auf GitHub zusammenführen („Merge“), damit der
   nächste Chat auf der neuen App aufbaut. Den App-Host der Uhr (M1) gibt es seit G2 Watch 0.4.0; jede
   Uhr-App geht direkt.
4. Für Apps, die GeckoView brauchen (Even-Hub-Apps auf der Uhr, der Browser): erst den **Gecko-Test**
   (M2, [README](../../README.md#gecko-test-m2-auf-die-uhr-bringen-und-messen)) auf der Uhr laufen lassen
   und seinen Bericht `g2-gecko-bericht.txt` mit in den Chat geben.

## Der Text

```
Repo: MADTreasures/ER-G2-custom (Standard-Branch; ohne Repo-Zugriff die hochgeladene ZIP).

Ich baue Apps für meine Even Realities G2 mit Faceclaw-Firmware (Revision 35). Die Brille wird von
meiner Pixel Watch 5 (45 mm, LTE, Wear OS 7) über die Uhr-App „G2 Watch“ (Ordner app/) gesteuert.
Lies zuerst README.md, docs/app-entwicklung/00_LIES_MICH.md bis 07_Umsetzungsplan.md und
designer/README.md.

So gehst du vor:
1. Prüf im Code und in der Stand-Tabelle von 00, ob der App-Host auf der Uhr (M1) schon da ist.
   Wenn nicht, bau ihn zuerst nach 07 (M1, mit den Anschlüssen aus 03 §5.2) und sag mir das.
2. Wähl die Laufzeit nach 00 und begründe sie in einem Satz: normalerweise Uhr-App; Rechner-App nur,
   wenn sie viel rechnet; Even-Hub-Apps laufen auf der Uhr oder dem Handy, nie auf dem Rechner.
3. Braucht meine App einen weiteren Plattform-Teil, der noch fehlt (Rechner = M5, Mikrofon/Sensoren = M6
   usw.), sag mir zuerst, welcher Meilenstein fehlt und wie groß er ist, und warte auf mein Okay.
4. Wenn ich Seiten aus dem G2 Baukasten mitgebe, nimm sie; sonst entwirf die Seiten selbst nach 02.
5. Wo meine Beschreibung Lücken hat, triff sinnvolle Annahmen und nenn sie mir am Ende.

Regeln: Tests ohne Hardware (simulierte Brille, Fakes, Snapshot-Bilder), CI und Lint grün, Doku,
Stand-Tabelle in 00 und Bilder nachziehen, Versionsnummer erhöhen. Nichts als auf Hardware erprobt
bezeichnen. Evens Firmware nie ins Repo, kein Zugriff auf Evens Store-Server, Firmware-Pfad nicht
anfassen (FlashingBoundaryTest bleibt grün). Code und Commits auf Englisch, Erklärungen für mich auf
Deutsch.

Am Ende: Committe, pushe und öffne einen Pull Request auf den Standard-Branch. Erklär mir kurz, was
gebaut ist, wie ich es mit Android Studio auf die Uhr bringe und was ich auf der echten Brille
ausprobieren soll, und gib mir eine ZIP des Projekts für Android Studio.

Meine App:
```

## Plattform-Teile ohne eigene App

Wer einen Meilenstein aus dem [Umsetzungsplan](07_Umsetzungsplan.md) allein bauen lassen will, nimmt
denselben Text und schreibt nach „Meine App:“ stattdessen eine Zeile aus dieser Tabelle:

| Meilenstein | Zeile |
|---|---|
| M0 | Keine App – setze M0 um (Baukasten an die App-Fläche anpassen, Kennungen, Bild-Baustein, Knopf „Zurück“) und veröffentliche den Baukasten unter derselben Adresse neu. |
| M1 | (erledigt in 0.4.0) Keine App – setze M1 um: App-Host auf der Uhr nach 03 §5 mit Stoppuhr und Einkaufsliste. |
| M2 | (Test-APK gebaut in 0.4.0) Keine App – setze M2 um: Test-APK mit GeckoView nach 05 §5.1. Sag mir genau, wie ich sie installiere und welche Werte ich dir zurückmelde. |
| M2 auswerten | Keine App – hier ist der Bericht des Gecko-Tests (unten eingefügt): trag die Werte in quellen/E-m2-messwerte.md ein, entscheide nach 05 §5.1 und zieh 00, 05 und 07 nach. |
| M3 | Keine App – setze M3 um: EvenHub-Laufzeit auf der Uhr nach 05 §4–§5 mit Test-App für alle Methoden. |
| M4 | Keine App – setze M4 um: Handy-App „G2 Handy“ nach 05 §6 und 06 §1. |
| M5 | Keine App – setze M5 um: Protokoll g2-remote@1, g2-host mit Web-Seite, Rechner-Client und Kopplung, Beispiel-Apps echo, pc-status, notizen. |
| M6 | Keine App – setze M6 um: Sensoren, Mikrofon (LC3), Summer und Standort bis zu den Apps, Beispiele „Diktat“ und „Kompass“. |
| M7 | Keine App – setze M7 um: Web-Browser auf der Brille nach 05 §10 und 07 M7 (`web-raster` ist schon da; nur nach „GeckoView ja“ aus M2, Bericht unten eingefügt). |

In claude.ai kann Claude einen Entwurf auch direkt aus dem Baukasten lesen („Schau dir meinen Baukasten an“).
