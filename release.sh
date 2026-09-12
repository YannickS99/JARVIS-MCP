#!/usr/bin/env bash
#
# Fuehrt einen vollstaendigen Git-Flow-Release durch - nicht nur den Versions-Bump.
#
#   ./release.sh 1.0.0
#   ./release.sh            # zeigt die aktuelle Version
#
# Ablauf (siehe Obsidian-Notiz "Release-Skript - Git-Flow-Standard"):
#
#   1. Vorbedingungen pruefen    6. git flow release finish (nur main + Tag)
#   2. Build und Tests           7. develop nachziehen (merge main -> develop)
#   3. git flow release start    8. develop auf die naechste Patch-Version mit -SNAPSHOT
#   4. Version bumpen            9. main samt Tag pushen
#   5. Commit im Release-Branch 10. develop pushen
#
# Schritt 1 und 2 fassen das Repository nicht an: Erst wenn der Stand nachweislich
# baut und alle Tests gruen sind, entsteht ueberhaupt ein Release-Branch.
#
# Gepusht wird erst, wenn lokal alles fertig ist (Schritt 9/10). Ein Fehlschlag
# unterwegs bleibt damit ein rein lokales Problem - auf GitHub ist dann nichts.
#
# Bump-Stelle dieses Projekts: pom.xml (Spring Boot / Maven).
#
# Die beiden Commits heissen einheitlich "Version bump to <version>" - im Release-Branch mit der
# Freigabeversion, auf develop mit der naechsten Patch-Version samt -SNAPSHOT. Dieselbe Schreibweise
# nutzen die uebrigen JARVIS-Projekte, damit ein Blick in den Verlauf ueberall dasselbe zeigt.

set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly POM="${ROOT_DIR}/pom.xml"
readonly TOTAL_STEPS=10

# Freigabeversionen sind immer X.Y.Z - Vorabkennzeichnungen wie -rc.1 haetten in diesem Ablauf
# keinen Platz, weil Schritt 8 daraus die naechste Patch-Version ableitet.
readonly VERSION_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+$'

readonly C_RESET=$'\033[0m'
readonly C_STEP=$'\033[1;36m'
readonly C_OK=$'\033[0;32m'
readonly C_FAIL=$'\033[0;31m'
readonly C_DIM=$'\033[0;90m'

CURRENT_STEP=0
# Wird gesetzt, sobald ein Release-Branch existiert - die Fehlerbehandlung erwaehnt ihn dann.
RELEASE_BRANCH=""
DEVELOP=""
MAIN=""

step() {
    CURRENT_STEP=$((CURRENT_STEP + 1))
    # Zuruecksetzen, damit `fail` nie die Ausgabe eines frueheren, gelungenen
    # Schrittes anhaengt und damit auf die falsche Faehrte fuehrt.
    LAST_OUTPUT=""
    printf '%s==> [%d/%d] %s%s ... ' "${C_STEP}" "${CURRENT_STEP}" "${TOTAL_STEPS}" "$1" "${C_RESET}"
}

step_ok()   { printf '%s✅%s\n' "${C_OK}" "${C_RESET}"; }
note()      { printf '    %s%s%s\n' "${C_DIM}" "$*" "${C_RESET}"; }

# Fuehrt einen Befehl aus und haelt dessen Ausgabe zurueck - aber nur, solange er gelingt.
# Scheitert er, wird alles gezeigt, was er gesagt hat. Die Ausgabe stumm wegzuwerfen hat beim
# ersten echten Release (JARVIS-MCP) genau das verdeckt, worauf es ankam: Sichtbar war nur "ist
# fehlgeschlagen (Merge-Konflikt?)", waehrend die eigentliche Meldung eine ganz andere Ursache nannte.
LAST_OUTPUT=""
run() {
    LAST_OUTPUT="$("$@" 2>&1)" && return 0
    local status=$?
    return ${status}
}

# Bricht mit einer Zustandsbeschreibung ab: Wichtiger als die Fehlerursache ist, in welchem
# Zustand das Repository jetzt ist und was von Hand nachzuholen bleibt.
fail() {
    printf '%s❌%s\n' "${C_FAIL}" "${C_RESET}"
    printf '\n%sFehler: %s%s\n' "${C_FAIL}" "$*" "${C_RESET}" >&2
    if [[ -n "${LAST_OUTPUT}" ]]; then
        printf '\n%sAusgabe des fehlgeschlagenen Befehls:%s\n' "${C_DIM}" "${C_RESET}" >&2
        printf '%s\n' "${LAST_OUTPUT}" | sed 's/^/    /' >&2
    fi
    if [[ -n "${RELEASE_BRANCH}" ]] && git -C "${ROOT_DIR}" rev-parse --verify --quiet "${RELEASE_BRANCH}" >/dev/null; then
        printf '\n%sDer Release-Branch %s existiert noch und wurde nicht abgeschlossen.\n' "${C_DIM}" "${RELEASE_BRANCH}" >&2
        printf 'Aktueller Branch: %s\n' "$(git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD)" >&2
        printf 'Aufraeumen z. B. mit: git checkout %s && git branch -D %s%s\n' \
            "${DEVELOP:-develop}" "${RELEASE_BRANCH}" "${C_RESET}" >&2
    fi
    exit 1
}

usage() {
    cat <<EOF
Verwendung: ./release.sh <version>

  <version>   Freigabeversion im Format X.Y.Z, z. B. 1.0.0

Fuehrt den kompletten Git-Flow-Release durch: Build und Tests pruefen, Release-Branch anlegen,
Version in der pom.xml setzen, committen, nach main und develop mergen, main taggen, develop auf
die naechste Patch-Version mit -SNAPSHOT hochziehen und beides pushen.

Gebaut und getestet wird, bevor irgendetwas am Repository geaendert wird.
EOF
}

# --- Version lesen und setzen -------------------------------------------------------------------

# Liest die Projektversion: das erste <version> nach dem Parent-Block. Bewusst ohne Maven-Aufruf,
# damit die blosse Anzeige ohne JVM-Start auskommt.
project_version() {
    awk '
        /<\/parent>/ { after_parent = 1; next }
        after_parent && match($0, /<version>[^<]+<\/version>/) {
            print substr($0, RSTART + 9, RLENGTH - 19)
            exit
        }
    ' "${POM}"
}

# Setzt die Version ueber das Maven-Plugin statt per Textersatz: Das Plugin kennt den Aufbau der
# pom.xml und fasst nur die Projektversion an, die Parent-Version bleibt unberuehrt.
#
# Nachgelesen wird trotzdem. Bei Python ist die Kontrolle Pflicht, weil BSD-sed (macOS) bei
# falscher Syntax stillschweigend nichts ersetzt; hier faellt dieser Grund zwar weg, aber ein
# halb durchgelaufener Release, der aussieht als waere alles gut gegangen, ist so oder so das
# teuerste Ergebnis - und die Pruefung kostet nichts.
set_version() {
    local target="$1" output
    output="$(mktemp)"
    if ! ( cd "${ROOT_DIR}" && ./mvnw -B -q versions:set \
            -DnewVersion="${target}" \
            -DgenerateBackupPoms=false \
            -DprocessAllModules=true ) >"${output}" 2>&1; then
        LAST_OUTPUT="$(cat "${output}")"
        rm -f "${output}"
        return 1
    fi
    rm -f "${output}"

    local actual
    actual="$(project_version)"
    [[ "${actual}" == "${target}" ]] || {
        printf 'pom.xml steht nach dem Setzen auf "%s" statt auf "%s"\n' "${actual}" "${target}" >&2
        return 1
    }
}

# --- Build und Tests ----------------------------------------------------------------------------

# Der Nachweis, dass der Stand taugt, den wir gleich freigeben.
#
# `clean install` statt `test`: Der Aufruf raeumt target/ erst weg, baut dann das Artefakt und
# laesst dabei die Tests laufen. Damit ist nicht nur "die Tests sind gruen" belegt, sondern auch
# "aus diesem Stand entsteht ein Jar" - und zwar ohne Reste eines frueheren Builds, die einen
# Fehler verdecken koennten. Genau das ist die Zusicherung, die ein Release gibt.
#
# Gebaut wird mit dem Wrapper des Projekts, nicht mit einem Maven vom System: Nur so gilt die
# Maven-Version, die das Projekt festlegt. (Beim JARVIS-AIService steht an dieser Stelle aus
# demselben Grund .venv/bin/python statt python3.)
run_build() {
    ( cd "${ROOT_DIR}" && ./mvnw -B clean install )
}

# --- Aufruf ohne Argument: nur anzeigen ---------------------------------------------------------

[[ -f "${POM}" ]] || fail "pom.xml nicht gefunden - laeuft das Skript im Projektwurzelverzeichnis?"

if [[ $# -eq 0 ]]; then
    printf 'Aktuelle Version: %s\n\n' "$(project_version)"
    usage
    exit 0
fi

if [[ "$1" == "-h" || "$1" == "--help" ]]; then
    usage
    exit 0
fi

[[ $# -eq 1 ]] || fail "Es wird genau ein Argument erwartet: die neue Version"

readonly VERSION="$1"

printf '\n%sRelease %s%s\n\n' "${C_STEP}" "${VERSION}" "${C_RESET}"

# --- [1/10] Vorbedingungen ----------------------------------------------------------------------

step "Vorbedingungen pruefen"

[[ "${VERSION}" =~ ${VERSION_PATTERN} ]] \
    || fail "'${VERSION}' ist keine gueltige Freigabeversion (erwartet: X.Y.Z, z. B. 1.0.0)"

git -C "${ROOT_DIR}" rev-parse --git-dir >/dev/null 2>&1 \
    || fail "Kein Git-Repository"

command -v git >/dev/null 2>&1 || fail "git wird benoetigt, ist aber nicht installiert"

# Verlangt git-flow-next. Das alte nvie-git-flow (0.4.1, in Homebrew als "git-flow" und dort seit
# 2026 deprecated) taugt hier nicht: Es reicht seine Optionen ueber shFlags an getopt weiter, und
# das BSD-getopt von macOS kann keinen Optionswert mit Leerzeichen - die Tag-Nachricht unten
# braechte es mit "flags:FATAL the available getopt does not support spaces in options" zu Fall.
git flow version >/dev/null 2>&1 \
    || fail "git flow ist nicht installiert (brew install git-flow-next)"

git flow version 2>/dev/null | grep -q 'git-flow-next' \
    || fail "Es wird git-flow-next gebraucht, installiert ist '$(git flow version 2>&1 | head -1)'.
       Wechseln mit: brew uninstall git-flow && brew install git-flow-next"

# git-flow-next benutzt ein eigenes Konfigurationsschema. Ein ueber SourceTree initialisiertes
# Repo - so wie alle JARVIS-Projekte - traegt nur das alte Schema und gilt hier als nicht
# initialisiert. `git flow init -d` ergaenzt das neue Schema, laesst das alte unangetastet und
# aendert nichts an der Nutzung in SourceTree.
[[ "$(git -C "${ROOT_DIR}" config --get gitflow.initialized || true)" == "true" ]] \
    || fail "git flow ist in diesem Repository nicht initialisiert (git flow init -d)"

# git-flow-next legt die Zweignamen nicht als feste Schluessel ab, sondern beschreibt jeden Zweig
# ueber seine Rolle. Die Angaben zum Release-Zweig nennen beides, was hier gebraucht wird: woher
# er kommt (startpoint = develop) und wohin er muendet (parent = main bzw. master). Damit
# funktioniert das Skript auch dort, wo der Hauptzweig noch master heisst.
DEVELOP="$(git -C "${ROOT_DIR}" config --get gitflow.branch.release.startpoint || true)"
MAIN="$(git -C "${ROOT_DIR}" config --get gitflow.branch.release.parent || true)"
readonly RELEASE_PREFIX="$(git -C "${ROOT_DIR}" config --get gitflow.branch.release.prefix || true)"
readonly BRANCH="$(git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD)"

[[ -n "${DEVELOP}" && -n "${MAIN}" ]] \
    || fail "Die git-flow-Konfiguration nennt keinen Release-Zweig (git flow init -d)"

[[ "${BRANCH}" == "${DEVELOP}" ]] \
    || fail "Ein Release startet auf '${DEVELOP}', aktueller Branch ist aber '${BRANCH}'"

git -C "${ROOT_DIR}" diff --quiet && git -C "${ROOT_DIR}" diff --cached --quiet \
    || fail "Das Arbeitsverzeichnis ist nicht sauber - bitte erst committen oder verwerfen"

# Ein lokal vorhandener Tag heisst zweierlei - je nachdem, ob er schon auf origin liegt. Nur im
# zweiten Fall ist die Version wirklich draussen; sonst ist ein frueherer Lauf steckengeblieben,
# und der Ausweg ist ein anderer. Diesen Unterschied zu verschweigen war beim Abbruch von 1.1.0
# das eigentliche Aergernis: Die Meldung behauptete, die Version sei veroeffentlicht.
if git -C "${ROOT_DIR}" rev-parse --verify --quiet "refs/tags/${VERSION}" >/dev/null; then
    if git -C "${ROOT_DIR}" ls-remote --exit-code --tags origin "${VERSION}" >/dev/null 2>&1; then
        fail "Der Tag '${VERSION}' liegt bereits auf origin - diese Version ist veroeffentlicht"
    fi
    fail "Der Tag '${VERSION}' existiert lokal, aber nicht auf origin.
       Ein frueherer Lauf ist nach dem Merge nach ${MAIN} abgebrochen; gepusht wurde nichts.
       Diesen halben Stand zuruecknehmen und neu anfangen:

           git tag -d ${VERSION}
           git branch -f ${MAIN} origin/${MAIN}
           git branch -D ${RELEASE_PREFIX}${VERSION}   # nur falls noch vorhanden
           git checkout ${DEVELOP}"
fi

git -C "${ROOT_DIR}" rev-parse --verify --quiet "${RELEASE_PREFIX}${VERSION}" >/dev/null \
    && fail "Der Branch '${RELEASE_PREFIX}${VERSION}' existiert bereits - ein frueherer Lauf wurde nicht abgeschlossen"

# Liegt main lokal vor origin, ohne dass ein Tag dazu existiert, stimmt etwas nicht - ein
# Release wuerde darauf aufsetzen und den fremden Stand mitveroeffentlichen.
if [[ -n "$(git -C "${ROOT_DIR}" log --oneline "origin/${MAIN}..${MAIN}" 2>/dev/null)" ]]; then
    fail "'${MAIN}' ist lokal weiter als origin/${MAIN} - vermutlich Reste eines abgebrochenen Laufs.
       Nachsehen mit: git log --oneline origin/${MAIN}..${MAIN}"
fi

# Schritt 2 und 4 brauchen ihn beide. Hier zu scheitern kostet nichts, im Build waere es
# vergeudete Wartezeit.
[[ -x "${ROOT_DIR}/mvnw" ]] || fail "mvnw fehlt oder ist nicht ausfuehrbar"

step_ok
note "${DEVELOP} → ${MAIN}, aktuelle Version: $(project_version) (noch nichts veraendert)"

# --- [2/10] Build und Tests ---------------------------------------------------------------------

# Vor dem ersten Eingriff ins Repository: Baut der Stand ueberhaupt, und sind die Tests gruen?
# Scheitert es hier, ist nichts angelegt, nichts committet, nichts gepusht - der Lauf kostet dann
# nur die Zeit des Builds.
step "Build und Tests"
if ! run run_build; then
    # Mavens Rohausgabe beginnt mit JVM-Warnungen und laeuft ueber hunderte Zeilen; der rote Test
    # oder der Compilerfehler steht in den [ERROR]-Zeilen mittendrin. Die herauszuziehen ist der
    # ganze Zweck, die Ausgabe ueberhaupt zu zeigen - ungefiltert waere sie so unbrauchbar wie gar
    # keine. Greift der Filter nicht (Maven gar nicht erst gestartet), bleibt das Ende stehen.
    filtered="$(printf '%s' "${LAST_OUTPUT}" | grep -E '^\[ERROR\]' | head -40 || true)"
    [[ -n "${filtered}" ]] || filtered="$(printf '%s' "${LAST_OUTPUT}" | tail -20)"
    LAST_OUTPUT="${filtered}"
    fail "Build bzw. Tests sind fehlgeschlagen - dieser Stand wird nicht freigegeben"
fi
step_ok
# Die letzte Zeile eines Maven-Laufs ist eine Trennlinie und sagt nichts. Interessant sind die
# Gesamtbilanz der Tests und die Laufzeit. Die Bilanzzeile ist die einzige "Tests run:"-Zeile
# ohne " -- in " - die uebrigen zaehlen je Testklasse und waeren hier nur Rauschen.
note "$(printf '%s' "${LAST_OUTPUT}" | awk '
    /^\[INFO\] Tests run:/ && !/ -- in /  { sub(/^\[INFO\] /, ""); tests = $0 }
    /^\[INFO\] Total time:/               { sub(/^\[INFO\] /, ""); total = $0 }
    END { if (tests != "") printf "%s", tests; if (total != "") printf " (%s)", total }
')"

# --- [3/10] Release-Branch ----------------------------------------------------------------------

step "Release-Branch ${RELEASE_PREFIX}${VERSION} erstellen"
run git -C "${ROOT_DIR}" flow release start "${VERSION}" \
    || fail "git flow release start ${VERSION} ist fehlgeschlagen"
RELEASE_BRANCH="${RELEASE_PREFIX}${VERSION}"
step_ok

# --- [4/10] Versions-Bump -----------------------------------------------------------------------

step "Version auf ${VERSION} setzen"
set_version "${VERSION}" || fail "Die Version konnte nicht auf ${VERSION} gesetzt werden"
step_ok

# --- [5/10] Commit ------------------------------------------------------------------------------

step "Versions-Bump committen"
run git -C "${ROOT_DIR}" add pom.xml || fail "git add pom.xml ist fehlgeschlagen"
# Stand die Version bereits auf dem Zielwert, hat Schritt 4 nichts geaendert und es gibt nichts zu
# committen. "git commit" scheitert dann - das ist hier aber kein Fehler, sondern der Normalfall
# eines zweiten Anlaufs oder eines von Hand vorgezogenen Bumps.
if git -C "${ROOT_DIR}" diff --cached --quiet; then
    step_ok
    note "Die Version stand bereits auf ${VERSION} - kein Commit noetig"
else
    run git -C "${ROOT_DIR}" commit -m "Version bump to ${VERSION}" \
        || fail "Der Commit des Versions-Bumps ist fehlgeschlagen"
    step_ok
fi

# --- [6/10] Release abschliessen ----------------------------------------------------------------

step "Release abschliessen (merge nach ${MAIN}, Tag setzen)"
# Merge, Tag und das Aufraeumen des Release-Branches macht git flow selbst - dafuer ist es da.
#
# --no-ff ist hier nicht optional, sondern der Kern des Git-Flow-Verlaufsbildes: Ist main seit dem
# Abzweig unveraendert geblieben - der Normalfall -, koennte der Release-Branch einfach
# vorgespult werden. Dann verschwindet er aber spurlos, der Tag landet auf dem nackten
# Bump-Commit, und im Verlauf sieht es aus, als waere die Version direkt auf main gesetzt worden.
#
# -m setzt die Tag-Nachricht mit, damit kein Editor aufgeht und der Lauf nicht haengt.
# --no-push, weil die Pushes bewusst als eigene Schritte folgen.
#
# `gitflow.branch.develop.autoupdate` wird fuer diesen Aufruf ausgeschaltet. Eingeschaltet zieht
# git-flow-next `develop` gleich selbst nach - und genau daran ist der Lauf fuer 1.1.0 gescheitert
# ("fatal: stash failed"), und zwar *nachdem* der Merge nach main und der Tag schon standen. Das
# Repository blieb halb freigegeben zurueck: main fertig, develop unberuehrt.
#
# Der Rueck-Merge ist deshalb ein eigener Schritt weiter unten. Er macht dasselbe, ist aber
# sichtbar, und wenn er scheitert, steht in der Schrittanzeige, wo es klemmt. `-c` gilt nur fuer
# diesen einen Aufruf; die Einstellung im Repository bleibt, wie sie ist.
run git -C "${ROOT_DIR}" -c gitflow.branch.develop.autoupdate=false \
    flow release finish -m "Release ${VERSION}" --no-ff --no-push "${VERSION}" \
    || fail "git flow release finish ${VERSION} ist fehlgeschlagen (Merge-Konflikt?)"
RELEASE_BRANCH=""
step_ok

# --- [7/10] develop nachziehen ------------------------------------------------------------------

# Der Rueck-Merge, den git flow sonst selbst macht. --no-ff aus demselben Grund wie oben: Ohne die
# Option wuerde develop einfach auf main vorgespult, und der Verlauf verloere die Zweigform.
step "${DEVELOP} von ${MAIN} nachziehen"
run git -C "${ROOT_DIR}" checkout "${DEVELOP}" \
    || fail "Wechsel auf ${DEVELOP} fehlgeschlagen"
if git -C "${ROOT_DIR}" merge-base --is-ancestor "${MAIN}" "${DEVELOP}"; then
    step_ok
    note "${DEVELOP} enthaelt ${MAIN} bereits - kein Merge noetig"
else
    run git -C "${ROOT_DIR}" merge --no-ff "${MAIN}" \
        -m "Merge branch '${MAIN}' into ${DEVELOP}" \
        || fail "Der Merge von ${MAIN} nach ${DEVELOP} ist fehlgeschlagen (Merge-Konflikt?).
       ${MAIN} und der Tag ${VERSION} stehen lokal bereits, gepusht ist nichts.
       Nach dem Aufloesen weiter mit: git commit && ./release.sh ${VERSION}"
    step_ok
fi

# --- [8/10] develop hochziehen ------------------------------------------------------------------

# Git Flow laesst nach dem Finish auf develop stehen - dort geht die Entwicklung auf der naechsten
# Patch-Version weiter, als Vorabstand gekennzeichnet.
readonly NEXT_VERSION="$(awk -F. '{ printf "%s.%s.%s-SNAPSHOT", $1, $2, $3 + 1 }' <<<"${VERSION}")"

step "${DEVELOP} auf ${NEXT_VERSION} setzen"
set_version "${NEXT_VERSION}" || fail "Die Version konnte nicht auf ${NEXT_VERSION} gesetzt werden"
run git -C "${ROOT_DIR}" add pom.xml || fail "git add pom.xml ist fehlgeschlagen"
if git -C "${ROOT_DIR}" diff --cached --quiet; then
    step_ok
    note "Die Version stand bereits auf ${NEXT_VERSION} - kein Commit noetig"
else
    run git -C "${ROOT_DIR}" commit -m "Version bump to ${NEXT_VERSION}" \
        || fail "Der Commit der Entwicklungsversion ist fehlgeschlagen"
    step_ok
fi

# --- [9/10] main pushen -------------------------------------------------------------------------

# Erst jetzt, wo lokal alles steht: Bis hierher war jeder Fehlschlag ein rein lokales Problem.
step "${MAIN} samt Tag pushen"
run git -C "${ROOT_DIR}" push origin "${MAIN}" \
    || fail "Der Push von ${MAIN} ist fehlgeschlagen - der Release liegt lokal bereits vollstaendig vor"
run git -C "${ROOT_DIR}" push origin "${VERSION}" \
    || fail "Der Push des Tags ${VERSION} ist fehlgeschlagen"
step_ok

# --- [10/10] develop pushen ---------------------------------------------------------------------

step "${DEVELOP} pushen"
run git -C "${ROOT_DIR}" push origin "${DEVELOP}" \
    || fail "Der Push von ${DEVELOP} ist fehlgeschlagen - ${MAIN} samt Tag ist bereits veroeffentlicht.
       Nachholen mit: git push origin ${DEVELOP}"
step_ok

printf '\n%s✅ Release %s ist veroeffentlicht.%s\n' "${C_OK}" "${VERSION}" "${C_RESET}"
note "Tag ${VERSION} auf ${MAIN}, ${DEVELOP} steht auf ${NEXT_VERSION}."
note "Build und Tests liefen vor dem Release gegen genau diesen Stand."
note "Auf JARVIS neu bauen: docker compose up -d --build"
