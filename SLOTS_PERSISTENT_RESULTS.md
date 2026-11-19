# Slots: resultaten blijven zichtbaar

Deze update is toegepast boven op `CasinoCraft-26.3-grounded-obstacle-pass-horses.zip`.

- De drie gerolde symbolen blijven zichtbaar in hun slots (21, 22, 23) na afloop.
- Het resultaat staat in hetzelfde chest-GUI in plaats van een apart, na vijf seconden sluitend resultaatvenster.
- De knop `DRAAI OPNIEUW` blijft in slot 49 staan en gebruikt de geselecteerde inzet.
- Tijdens de animatie zijn inzetten en opnieuw draaien geblokkeerd.
- De symbolen en uitbetaling worden na de draai opgeslagen in de spelerssessie (zolang die sessie bestaat).
- De andere casinospellen houden de bestaande resultaatvensters.

## Build
Vervang projectbestanden in je GitHub-repository en laat de bestaande GitHub Actions-build met Java 25 en Gradle 9.8 draaien. De source is hier niet gecompileerd of op een server getest.
