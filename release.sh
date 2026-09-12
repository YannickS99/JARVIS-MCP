#!/usr/bin/env bash
#
# Fuehrt einen vollstaendigen Git-Flow-Release durch - nicht nur den Versions-Bump.
#
#   ./release.sh 1.0.0
#   ./release.sh            # zeigt die aktuelle Version
#
# Ablauf (siehe Obsidian-Notiz "Release-Skript - Git-Flow-Standard"):
#
#   1. Vorbedingungen pruefen   5. git flow release finish
#   2. git flow release start   6. main pushen (inkl. Tag)
#   3. Version bumpen           7. develop auf die naechste Patch-Version mit -SNAPSHOT
#   4. Commit im Release-Branch 8. develop pushen
#
# Bump-Stelle dieses Projekts: pom.xml (Spring Boot / Maven).
#
# Die beiden Commits heissen einheitlich "Version bump to <version>" - im Release-Branch mit der
# Freigabeversion, auf develop mit der naechsten Patch-Version samt -SNAPSHOT. Dieselbe Schreibweise
# nutzen die uebrigen JARVIS-Projekte, damit ein Blick in den Verlauf ueberall dasselbe zeigt.

set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly POM="${ROOT_DIR}/pom.xml"
readonly TOTAL_STEPS=8

# Freigabeversionen sind immer X.Y.Z - Vorabkennzeichnungen wie -rc.1 haetten in diesem Ablauf
# keinen Platz, weil Schritt 7 daraus die naechste Patch-Version ableitet.
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
    printf '%s==> [%d/%d] %s%s ... ' "${C_STEP}" "${CURRENT_STEP}" "${TOTAL_STEPS}" "$1" "${C_RESET}"
}

step_ok()   { printf '%s✅%s\n' "${C_OK}" "${C_RESET}"; }
note()      { printf '    %s%s%s\n' "${C_DIM}" "$*" "${C_RESET}"; }

# Bricht mit einer Zustandsbeschreibung ab: Wichtiger als die Fehlerursache ist, in welchem
# Zustand das Repository jetzt ist und was von Hand nachzuholen bleibt.
# Fuehrt einen Befehl aus und haelt dessen Ausgabe zurueck - aber nur, solange er gelingt.
# Scheitert er, wird alles gezeigt, was er gesagt hat. Die Ausgabe stumm wegzuwerfen hat beim
# ersten echten Release genau das verdeckt, worauf es ankam (siehe Kommentar bei Schritt 5).
LAST_OUTPUT=""
run() {
    LAST_OUTPUT="$("$@" 2>&1)" && return 0
    local status=$?
    return ${status}
}

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

Fuehrt den kompletten Git-Flow-Release durch: Release-Branch anlegen, Version in der pom.xml
setzen, committen, nach main und develop mergen, main taggen, beides pushen und develop auf die
naechste Patch-Version mit -SNAPSHOT hochziehen.
EOF
}

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
# pom.xml und fasst nur die Projektversion an, die Parent-Version bleibt unberuehrt. Anschliessend
# wird nachgelesen - ein stillschweigend wirkungsloser Ersatz (die bekannte BSD-sed-Falle aus dem
# JARVIS-AIService) faellt so sofort auf, statt einen halb durchgelaufenen Release zu hinterlassen.
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

# --- [1/8] Vorbedingungen -----------------------------------------------------------------------

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

[[ "$(git -C "${ROOT_DIR}" config --get gitflow.initialized || true)" == "true" ]] \
    || fail "git flow ist in diesem Repository nicht initialisiert (git flow init -d)"

# git-flow-next legt die Zweignamen nicht als feste Schluessel ab, sondern beschreibt jeden Zweig
# ueber seine Rolle. Die Angaben zum Release-Zweig nennen beides, was hier gebraucht wird: woher
# er kommt (startpoint = develop) und wohin er muendet (parent = main). Damit funktioniert das
# Skript auch, wenn die Zweige einmal anders heissen sollten.
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

git -C "${ROOT_DIR}" rev-parse --verify --quiet "refs/tags/${VERSION}" >/dev/null \
    && fail "Der Tag '${VERSION}' existiert bereits - diese Version wurde schon veroeffentlicht"

git -C "${ROOT_DIR}" rev-parse --verify --quiet "${RELEASE_PREFIX}${VERSION}" >/dev/null \
    && fail "Der Branch '${RELEASE_PREFIX}${VERSION}' existiert bereits - ein frueherer Lauf wurde nicht abgeschlossen"

[[ -x "${ROOT_DIR}/mvnw" ]] || fail "mvnw fehlt oder ist nicht ausfuehrbar"

step_ok
note "${DEVELOP} → ${MAIN}, aktuelle Version: $(project_version)"

# --- [2/8] Release-Branch -----------------------------------------------------------------------

step "Release-Branch ${RELEASE_PREFIX}${VERSION} erstellen"
run git -C "${ROOT_DIR}" flow release start "${VERSION}" \
    || fail "git flow release start ${VERSION} ist fehlgeschlagen"
RELEASE_BRANCH="${RELEASE_PREFIX}${VERSION}"
step_ok

# --- [3/8] Versions-Bump ------------------------------------------------------------------------

step "Version in pom.xml auf ${VERSION} setzen"
set_version "${VERSION}" || fail "Die Version konnte nicht auf ${VERSION} gesetzt werden"
step_ok

# --- [4/8] Commit -------------------------------------------------------------------------------

step "Versions-Bump committen"
run git -C "${ROOT_DIR}" add pom.xml || fail "git add pom.xml ist fehlgeschlagen"
# Stand die Version bereits auf dem Zielwert, hat Schritt 3 nichts geaendert und es gibt nichts zu
# committen. "git commit" scheitert dann - das ist hier aber kein Fehler, sondern der Normalfall
# eines zweiten Anlaufs oder eines von Hand vorgezogenen Bumps.
if git -C "${ROOT_DIR}" diff --cached --quiet; then
    step_ok
    note "pom.xml stand bereits auf ${VERSION} - kein Commit noetig"
else
    run git -C "${ROOT_DIR}" commit -m "Version bump to ${VERSION}" \
        || fail "Der Commit des Versions-Bumps ist fehlgeschlagen"
    step_ok
fi

# --- [5/8] Release abschliessen -----------------------------------------------------------------

step "Release abschliessen (merge nach ${MAIN} und ${DEVELOP}, Tag setzen)"
# Merge, Tag und das Aufraeumen des Release-Branches macht git flow selbst - dafuer ist es da.
#
# --no-ff ist hier nicht optional, sondern der Kern des Git-Flow-Verlaufsbildes: Ist main seit dem
# Abzweig unveraendert geblieben - der Normalfall -, koennte der Release-Branch einfach
# vorgespult werden. Dann verschwindet er aber spurlos, der Tag landet auf dem nackten
# Bump-Commit, und im Verlauf sieht es aus, als waere die Version direkt auf main gesetzt worden.
# Mit --no-ff entsteht der uebliche "Merge branch 'release/X'"-Commit auf main, und der Tag sitzt
# darauf. Bewusst als Option im Skript statt als Repo-Konfiguration: git-flow-next kennt dafuer
# keinen Konfigurationsschluessel, und so haengt das Ergebnis nicht daran, wie ein Repo
# eingerichtet wurde.
#
# -m setzt die Tag-Nachricht mit, damit kein Editor aufgeht und der Lauf nicht haengt.
# --no-push, weil die Pushes bewusst als eigene Schritte folgen: So sagt die Schrittanzeige, was
# gerade passiert, und ein gescheiterter Push ist von einem gescheiterten Merge unterscheidbar.
run git -C "${ROOT_DIR}" flow release finish -m "Release ${VERSION}" --no-ff --no-push "${VERSION}" \
    || fail "git flow release finish ${VERSION} ist fehlgeschlagen (Merge-Konflikt?)"
RELEASE_BRANCH=""
step_ok

# --- [6/8] main pushen --------------------------------------------------------------------------

step "${MAIN} samt Tag pushen"
run git -C "${ROOT_DIR}" push origin "${MAIN}" \
    || fail "Der Push von ${MAIN} ist fehlgeschlagen - der Release liegt lokal bereits vollstaendig vor"
run git -C "${ROOT_DIR}" push origin "${VERSION}" \
    || fail "Der Push des Tags ${VERSION} ist fehlgeschlagen"
step_ok

# --- [7/8] develop hochziehen -------------------------------------------------------------------

# Git Flow laesst nach dem Finish auf develop stehen - dort geht die Entwicklung auf der naechsten
# Patch-Version weiter, als Vorabstand gekennzeichnet.
readonly NEXT_VERSION="$(awk -F. '{ printf "%s.%s.%s-SNAPSHOT", $1, $2, $3 + 1 }' <<<"${VERSION}")"

step "${DEVELOP} auf ${NEXT_VERSION} setzen"
run git -C "${ROOT_DIR}" checkout "${DEVELOP}" \
    || fail "Wechsel auf ${DEVELOP} fehlgeschlagen"
set_version "${NEXT_VERSION}" || fail "Die Version konnte nicht auf ${NEXT_VERSION} gesetzt werden"
run git -C "${ROOT_DIR}" add pom.xml || fail "git add pom.xml ist fehlgeschlagen"
if git -C "${ROOT_DIR}" diff --cached --quiet; then
    step_ok
    note "pom.xml stand bereits auf ${NEXT_VERSION} - kein Commit noetig"
else
    run git -C "${ROOT_DIR}" commit -m "Version bump to ${NEXT_VERSION}" \
        || fail "Der Commit der Entwicklungsversion ist fehlgeschlagen"
    step_ok
fi

# --- [8/8] develop pushen -----------------------------------------------------------------------

step "${DEVELOP} pushen"
run git -C "${ROOT_DIR}" push origin "${DEVELOP}" \
    || fail "Der Push von ${DEVELOP} ist fehlgeschlagen"
step_ok

printf '\n%s✅ Release %s ist veroeffentlicht.%s\n' "${C_OK}" "${VERSION}" "${C_RESET}"
note "Tag ${VERSION} auf ${MAIN}, ${DEVELOP} steht auf ${NEXT_VERSION}."
