#!/bin/bash

LOADER="adf.impl.DefaultLoader"
MAP_NAME="unknown-map"
PARAMS=()

# This option labels local logs; it is not an ADF/server command-line option.
while [ "$#" -gt 0 ]; do
  case "$1" in
    --map-name)
      if [ "$#" -lt 2 ] || [ -z "$2" ]; then
        echo "Usage: bash launch.sh --map-name kobe1 -all" >&2
        exit 2
      fi
      MAP_NAME="$2"
      shift 2
      ;;
    --map-name=*)
      MAP_NAME="${1#*=}"
      shift
      ;;
    *)
      PARAMS+=("$1")
      shift
      ;;
  esac
done

cd -- "$(dirname -- "$0")" || exit 1

if [ "${#PARAMS[@]}" -gt 0 ]; then
  if [[ ! "$MAP_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]]; then
    echo "Invalid map name: use letters, numbers, ., _ or - (max 80 characters)." >&2
    exit 2
  fi
  # JavaExec --args uses quotes to keep arguments such as team names together.
  ARGS="${LOADER}"
  for PARAM in "${PARAMS[@]}"; do
    if [[ "$PARAM" != *\"* ]]; then
      ARGS+=" \"${PARAM}\""
    elif [[ "$PARAM" != *\'* ]]; then
      ARGS+=" '${PARAM}'"
    else
      echo "An argument cannot contain both single and double quotes; use a config file for that value." >&2
      exit 2
    fi
  done
  exec ./gradlew launch "-PmapName=${MAP_NAME}" "--args=${ARGS}"
else
  echo "Options:"
  echo "--map-name [NAME]                 Log archive label, e.g. kobe1 (does not select server map)"
  echo "-tn                              Team name"
  echo "-t [FB],[FS],[PF],[PO],[AT],[AC] Number of agents"
  echo "-fb [FB]                         Number of FireBrigade"
  echo "-fs [FS]                         Number of FireStation"
  echo "-pf [PF]                         Number of PoliceForce"
  echo "-po [PO]                         Number of PoliceOffice"
  echo "-at [AT]                         Number of AmbulanceTeam"
  echo "-ac [AC]                         Number of AmbulanceCentre"
  echo "-s [HOST]:[PORT]                 RCRS server host and port"
  echo "-h [HOST]                        RCRS server host (port:27931)"
  echo "-pre [0|1]                       Precompute flag"
  echo "-d [0|1]                         Debug flag"
  echo "-dev [0|1]                       Development mode"
  echo "-mc [FILE]                       ModuleConfig file name"
  echo "-md [JSON]                       ModuleConfig JSON"
  echo "-df [FILE]                       DevelopData JSON file"
  echo "-dd [JSON]                       DevelopData JSON"
  echo "-all                             [alias] -t -1,-1,-1,-1,-1,-1"
  echo "-allp                            [alias] -t 1,0,1,0,1,0,"
  echo "-local                           [alias] -h localhost"
  echo "-precompute                      [alias] -pre 1"
  echo "-debug                           [alias] -d 1"
  echo "-develop                         [alias] -dev 1"
fi
