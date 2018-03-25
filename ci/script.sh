#!/bin/bash

set -e

# Test
lein with-profile test-1.8:test-1.9 test

# Deploy API docs
git clone https://$GITHUB_TOKEN@github.com/suprematic/suprematic.github.io.git
ln -s suprematic.github.io/otplike/api docs
lein codox
cd suprematic.github.io
set +e
git diff --quiet
if [ $? -ne 0 ]; then
  set -e
  git add ./*
  git commit -m 'otplike: update api docs'
  git push
  cd -
fi
