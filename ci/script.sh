#!/bin/bash

set -e

# Test
lein with-profile test-1.8:test-1.9:test-1.10 test

# Deploy API docs
git clone https://$GITHUB_TOKEN@github.com/suprematic/suprematic.github.io.git
ln -s suprematic.github.io/otplike/api docs
lein codox
cd suprematic.github.io
STATUS=`git status -s`
if [ ${#STATUS} -ne 0 ]; then
  git add otplike/api
  git commit -m 'otplike: update api docs'
  git push
  cd -
fi
