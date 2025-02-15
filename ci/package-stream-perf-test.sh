#!/usr/bin/env bash

source $(pwd)/performance-tool.txt
source $(pwd)/release-versions.txt

if [ -z ${RELEASE+x} ]
then
  CURRENT_DATE=$(date --utc '+%Y%m%d-%H%M%S')
  RELEASE_VERSION="$(cat pom.xml | grep -oPm1 '(?<=<version>)[^<]+')-$CURRENT_DATE"
  FINAL_NAME="$PERF_TOOL_BASE_NAME-$RELEASE_VERSION"
fi

FINAL_NAME="$PERF_TOOL_BASE_NAME-$RELEASE_VERSION"

mkdir packages

./mvnw clean package checksum:files gpg:sign -Dgpg.skip=false -Dmaven.test.skip -P performance-tool -DfinalName="$FINAL_NAME" --no-transfer-progress

./mvnw test-compile exec:java -Dexec.mainClass="picocli.AutoComplete" -Dexec.classpathScope=test -Dexec.args="-f -n $PERF_TOOL_BASE_NAME com.rabbitmq.stream.perf.AggregatingCommandForAutoComplete" --no-transfer-progress
cp "$PERF_TOOL_BASE_NAME"_completion packages/"$PERF_TOOL_BASE_NAME"-"$RELEASE_VERSION"_completion

rm target/*.original
cp target/"$FINAL_NAME".jar packages
cp target/"$FINAL_NAME".jar.* packages

if [ -z ${RELEASE+x} ]
then
  mkdir packages-latest
  cp target/"$FINAL_NAME".jar packages-latest
  cp target/"$FINAL_NAME".jar.* packages-latest
  cp packages/*_completion packages-latest

  for filename in packages-latest/*; do
    [ -f "$filename" ] || continue
    filename_without_version=$(echo "$filename" | sed -e "s/$RELEASE_VERSION/latest/g")
    mv "$filename" "$filename_without_version"
  done
  echo "release_name=$PERF_TOOL_BASE_NAME-$RELEASE_VERSION" >> $GITHUB_ENV
  echo "tag_name=v-$PERF_TOOL_BASE_NAME-$RELEASE_VERSION" >> $GITHUB_ENV
else
  echo "release_name=$RELEASE_VERSION" >> $GITHUB_ENV
  echo "tag_name=v$RELEASE_VERSION" >> $GITHUB_ENV
fi

echo "release_version=$RELEASE_VERSION" >> $GITHUB_ENV
echo "release_branch=$RELEASE_BRANCH" >> $GITHUB_ENV
