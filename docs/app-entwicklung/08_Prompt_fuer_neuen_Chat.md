# 08 – Text für einen neuen Chat

## So geht's

1. Neuen Chat in **Claude Code** öffnen und das Repo `MADTreasures/ER-G2-custom` wählen. Es müssen
   **keine Dateien** hochgeladen werden: Der Standard-Branch enthält alles (Uhr-App, Baukasten, diese Mappe).
   Ohne Repo-Zugriff: die Projekt-ZIP (`G2Watch-Projekt.zip`) hochladen.
2. Den Text unten einfügen und nach **„Meine App:“** in eigenen Worten beschreiben, was die App tun soll.
   Seiten aus dem [G2 Baukasten](../../designer/README.md) („⋯“ → „Für den Chat kopieren“) können mit
   dazu, müssen aber nicht.
3. Am Ende öffnet der Chat einen **Pull Request**. Diesen auf GitHub zusammenführen („Merge“), damit der
   nächste Chat auf der neuen App aufbaut. **Erst zusammenführen, dann den nächsten Chat starten** –
   sonst baut jeder Chat die fehlenden Plattform-Teile (etwa den App-Host, M1) von neuem. Der App-Host (M1)
   ist seit v0.4.0 da, seit v0.5.0 auch Texteingabe auf der Uhr und Videos (03 §10).

## Der Text

```
Repo: MADTreasures/ER-G2-custom (Standard-Branch; ohne Repo-Zugriff die hochgeladene ZIP).

Ich baue Apps für meine Even Realities G2 mit Faceclaw-Firmware (Revision 35). Die Brille wird von
meiner Pixel Watch 5 (45 mm, LTE, Wear OS 7) über die Uhr-App „G2 Watch“ (Ordner app/) gesteuert.
Lies zuerst README.md, docs/app-entwicklung/00_LIES_MICH.md bis 07_Umsetzungsplan.md und
designer/README.md.

So gehst du vor:
1. Prüf im Code, in der Stand-Tabelle von 00 und in den offenen Pull Requests, ob der App-Host auf
   der Uhr (M1) schon da ist. Liegt er nur in einem offenen Pull Request, bau ihn nicht neu, sondern
   sag mir, welchen ich zuerst zusammenführen soll. Fehlt er ganz, bau ihn nach 07 (M1, mit den
   Anschlüssen aus 03 §5.2) und sag mir das.
2. Wähl die Laufzeit nach 00 und begründe sie in einem Satz: normalerweise Uhr-App; Rechner-App nur,
   wenn sie viel rechnet; Even-Hub-Apps laufen auf der Uhr oder dem Handy, nie auf dem Rechner.
3. Braucht meine App einen weiteren Plattform-Teil, der noch fehlt (Rechner = M5, Mikrofon/Sensoren = M6
   usw.), sag mir zuerst, welcher Meilenstein fehlt und wie groß er ist, und warte auf mein Okay.
4. Wenn ich Seiten aus dem G2 Baukasten mitgebe, nimm sie; sonst entwirf die Seiten selbst nach 02.
5. Wo meine Beschreibung Lücken hat, triff sinnvolle Annahmen und nenn sie mir am Ende.

Regeln: Tests ohne Hardware (simulierte Brille, Fakes, Snapshot-Bilder), CI und Lint grün, Doku,
Stand-Tabelle in 00 und Bilder nachziehen, Versionsnummer erhöhen. In den Starter auf der Brille
kommen nur meine Apps, keine Beispiel- oder Test-Apps. Nichts als auf Hardware erprobt bezeichnen.
Evens Firmware nie ins Repo, kein Zugriff auf Evens Store-Server, Firmware-Pfad nicht anfassen
(FlashingBoundaryTest bleibt grün). Code und Commits auf Englisch, Erklärungen für mich auf Deutsch.

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
| M1 | Keine App – setze M1 um: App-Host auf der Uhr nach 03 §5; Stoppuhr und Einkaufsliste nur als Test-Apps. |
| M2 | Keine App – setze M2 um: Test-APK mit GeckoView nach 05 §5.1. Sag mir genau, wie ich sie installiere und welche Werte ich dir zurückmelde. |
| M3 | Keine App – setze M3 um: EvenHub-Laufzeit auf der Uhr nach 05 §4–§5 mit Test-App für alle Methoden. |
| M4 | Keine App – setze M4 um: Handy-App „G2 Handy“ nach 05 §6 und 06 §1. |
| M5 | Keine App – setze M5 um: Protokoll g2-remote@1, g2-host mit Web-Seite, Rechner-Client und Kopplung, Beispiel-Apps echo, pc-status, notizen. |
| M6 | Keine App – setze M6 um: Sensoren, Mikrofon (LC3), Summer und Standort bis zu den Apps, Beispiele „Diktat“ und „Kompass“. |
| M7 | Keine App – setze M7 um: Web-Browser auf der Brille nach 05 §10. |

In claude.ai kann Claude einen Entwurf auch direkt aus dem Baukasten lesen („Schau dir meinen Baukasten an“).
