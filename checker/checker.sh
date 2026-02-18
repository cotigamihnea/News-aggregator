#!/bin/bash
date
hostname

# Config
THREADS_PAR=(2 4)
FULL_SPEEDUPS=(1.40 1.75)
TIMEOUT_BASE=200
TESTS=(test_1 test_2 test_3 test_4 test_5)

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
JAVA_DIR="${ROOT_DIR}/src"
TESTS_DIR="${ROOT_DIR}/checker/input/tests"
OUT_DIR="${ROOT_DIR}/checker/solution_output"
EXPECTED_BASE="${ROOT_DIR}/checker/output"

SMALL_MODE=0
if [ $# -eq 1 ]; then
    case "$1" in
        test_small)
            SMALL_MODE=1
            TESTS=("test_small")
            ;;
        test_1|test_2|test_3|test_4|test_5)
            TESTS=("$1")
            ;;
        *)
            echo "Usage: $0 [test_small|test_1|test_2|test_3|test_4|test_5]"
            exit 1
            ;;
    esac
fi

NUM_TESTS=${#TESTS[@]}
MAX_CORRECTNESS=$(( NUM_TESTS * 6 ))
if [ "$SMALL_MODE" -eq 1 ]; then
    MAX_SCALABILITY=0
else
    MAX_SCALABILITY=$(( NUM_TESTS * 9 ))
fi
MAX_TOTAL=$(( MAX_CORRECTNESS + MAX_SCALABILITY ))

scalability_points=0
correctness_points=0
seq_time=0

function format_number() {
    local n="$1"
    if [[ "$n" =~ ^-?[0-9]+\.0+$ ]]; then
        echo "${n%.*}"
    else
        echo "$n"
    fi
}

function show_score() {
    echo ""
    local f_scal
    f_scal=$(format_number "${scalability_points}")
    local f_corr
    f_corr=$(format_number "${correctness_points}")
    local total
    total=$(echo "scale=1; ${correctness_points} + ${scalability_points}" | bc)
    local f_total
    f_total=$(format_number "${total}")
    echo "Scalabilitate: ${f_scal}/${MAX_SCALABILITY}"
    echo "Corectitudine: ${f_corr}/${MAX_CORRECTNESS}"
    echo "Total:        ${f_total}/${MAX_TOTAL}"
}

function build_project() {
    echo "[BUILD] Building Java project..."
    pushd "$JAVA_DIR" > /dev/null || exit 1
    echo "[BUILD] make clean..."
    make clean
    echo "[BUILD] make build..."
    make build
    if [ $? -ne 0 ]; then
        echo "E: Nu s-a putut compila proiectul Java"
        popd > /dev/null
        show_score
        exit 1
    fi
    popd > /dev/null
    echo "[BUILD] Done"
}

function setup_scoring() {
    NUM_TESTS=${#TESTS[@]}
    MAX_CORRECTNESS=$(( NUM_TESTS * 6 ))
    if [ "$SMALL_MODE" -eq 1 ]; then
        MAX_SCALABILITY=0
    else
        MAX_SCALABILITY=$(( NUM_TESTS * 9 ))
    fi
    MAX_TOTAL=$(( MAX_CORRECTNESS + MAX_SCALABILITY ))
    scalability_points=0
    correctness_points=0
}

function compare_outputs() {
    # compare directories $1 and $2, ignoring whitespace differences
    diff -rq -w "$1" "$2" > /dev/null 2>&1
    return $?
}

function run_and_collect() {
    local threads=$1
    local outdir=$2
    local timeout=$3

    rm -rf "$outdir"
    mkdir -p "$outdir"

    pushd "$JAVA_DIR" > /dev/null || exit 1
    rm -f ./*.txt

    local timefile
    timefile=$(mktemp 2>/dev/null || echo "/tmp/checker_time_$$.txt")
    local timeout_cmd=""
    if command -v timeout >/dev/null 2>&1; then
        timeout_cmd="timeout ${timeout}"
    elif command -v gtimeout >/dev/null 2>&1; then
        timeout_cmd="gtimeout ${timeout}"
    fi

    { time -p sh -c "${timeout_cmd} make run ARGS=\"${threads} ${ARTICLES_FILE} ${INPUTS_FILE}\" > /dev/null"; } &> "${timefile}"
    local ret=$?

    # gather outputs regardless
    shopt -s nullglob
    for f in ./*.txt; do
        mv "$f" "$outdir/"
    done
    shopt -u nullglob

    if [ $ret -eq 124 ]; then
        echo "E: Programul a durat mai mult de ${timeout}s"
        if [ -f "${timefile}" ]; then
            cat "${timefile}" | sed '$d' | sed '$d' | sed '$d'
        fi
        popd > /dev/null || exit
        rm -f "${timefile}"
        return 124
    elif [ $ret -ne 0 ]; then
        echo "E: Rularea nu s-a executat cu succes"
        if [ -f "${timefile}" ]; then
            cat "${timefile}" | sed '$d' | sed '$d' | sed '$d'
        fi
        popd > /dev/null || exit
        rm -f "${timefile}"
        return 1
    fi

    local t=""
    if [ -f "${timefile}" ]; then
        t=$(grep '^real' "${timefile}" | awk '{print $2}')
    fi
    rm -f "${timefile}"
    popd > /dev/null || exit

    echo "$t"
    return 0
}

function run_test_case() {
    local TEST="$1"
    echo ""
    echo "========== ${TEST} =========="
    ARTICLES_FILE="${TESTS_DIR}/${TEST}/articles.txt"
    INPUTS_FILE="${TESTS_DIR}/${TEST}/inputs.txt"
    EXPECTED_DIR="${EXPECTED_BASE}/${TEST}"

    if [ ! -f "$ARTICLES_FILE" ] || [ ! -f "$INPUTS_FILE" ]; then
        echo "E: Missing test files for ${TEST}"
        return
    fi

    echo "[RUN] Rulare secventiala cu 1 thread..."
    seq_time=$(run_and_collect 1 "${OUT_DIR}/${TEST}/test_sec" ${TIMEOUT_BASE})
    if [ $? -ne 0 ]; then
        echo "W: Rulare secventiala esuata pentru ${TEST}"
        return
    fi
    compare_outputs "${EXPECTED_DIR}" "${OUT_DIR}/${TEST}/test_sec"
    if [ $? -eq 0 ]; then
        if [ "$SMALL_MODE" -eq 1 ]; then
            printf "  \xE2\x9C\x93 Corect\n"
        else
            printf "  \xE2\x9C\x93 Timp: %ss\n" "$seq_time"
        fi
        correctness_points=$((correctness_points+2))
    else
        echo "W: Verificarea pentru corectitudine a esuat pentru rularea cu 1 thread (difera de referinta)"
    fi

    local count=0
    for t in "${THREADS_PAR[@]}"; do
        echo ""
        echo "[RUN] Rulare paralela cu ${t} thread(s) (3 rulari)..."
        base_int=${seq_time%.*}
        timeout=$(( base_int + 2 ))
        total_time=0
        runs_ok=0
        for run in 1 2 3; do
            par_time=$(run_and_collect ${t} "${OUT_DIR}/${TEST}/test_par_${t}_run${run}" ${timeout})
            par_rc=$?
            if [ $par_rc -eq 0 ]; then
                compare_outputs "${EXPECTED_DIR}" "${OUT_DIR}/${TEST}/test_par_${t}_run${run}"
                if [ $? -eq 0 ]; then
                    runs_ok=$((runs_ok+1))
                    total_time=$(echo "${total_time} + ${par_time}" | bc)
                else
                    echo "W: Difera de referinta (run ${run}, ${t} thread-uri)"
                fi
            else
                echo "W: Rulare esuata/timeout (run ${run}, ${t} thread-uri)"
            fi
        done

        if [ $runs_ok -eq 3 ]; then
            correctness_points=$((correctness_points+2))
            if [ "$SMALL_MODE" -eq 0 ]; then
                avg_time=$(echo "scale=3; ${total_time} / 3.0" | bc)
                speedup=$(echo "scale=2; ${seq_time} / ${avg_time}" | bc | xargs printf "%.2f")
                printf "  \xE2\x9C\x93 Timp mediu: %ss, Acceleratie medie: %sx\n" "$avg_time" "$speedup"
                max=$(echo "${speedup} >= ${FULL_SPEEDUPS[$count]}" | bc -l)
                if [ "$max" -eq 1 ]; then
                    scalability_points=$(echo "${scalability_points} + 4.5" | bc)
                else
                    echo "W: Acceleratia medie prea mica (fara punctaj)"
                fi
            else
                printf "  \xE2\x9C\x93 Corect (3/3)\n"
            fi
        fi

        count=$((count+1))
    done
}

build_project

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

setup_scoring

for TEST in "${TESTS[@]}"; do
    run_test_case "$TEST"
done

echo ""
echo "=========================="
show_score

# Cleanup build artifacts and solution outputs
pushd "$JAVA_DIR" > /dev/null || exit 1
make clean > /dev/null 2>&1
popd > /dev/null || exit
rm -rf "$OUT_DIR"