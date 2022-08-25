// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#include "yb/yql/pggate/pg_perform_future.h"

#include <chrono>
#include <utility>

#include "yb/util/flag_tags.h"
#include "yb/yql/pggate/pg_session.h"

DEFINE_test_flag(bool, use_monotime_for_rpc_wait_time, false,
                 "Flag to enable use of MonoTime::Now() instead of CoarseMonoClock::Now() "
                 "in order to avoid 0 timings in the tests.");

using namespace std::literals;

namespace yb {
namespace pggate {

PerformFuture::PerformFuture(
    std::future<PerformResult> future, PgSession* session, PgObjectIds relations)
    : future_(std::move(future)), session_(session), relations_(std::move(relations)) {
}

bool PerformFuture::Valid() const {
  return session_ != nullptr;
}

bool PerformFuture::Ready() const {
  return Valid() && future_.wait_for(0ms) == std::future_status::ready;
}

Result<rpc::CallResponsePtr> PerformFuture::Get() {
  PgSession* session = nullptr;
  std::swap(session, session_);
  auto result = future_.get();
  session->TrySetCatalogReadPoint(result.catalog_read_time);
  RETURN_NOT_OK(session->PatchStatus(result.status, relations_));
  return result.response;
}

Result<rpc::CallResponsePtr> PerformFuture::Get(MonoDelta* wait_time) {
  if (PREDICT_FALSE(FLAGS_TEST_use_monotime_for_rpc_wait_time)) {
    auto start_time = MonoTime::Now();
    auto response = Get();
    *wait_time += MonoTime::Now() - start_time;
    return response;
  }

  auto start_time = CoarseMonoClock::Now();
  auto response = Get();
  *wait_time += CoarseMonoClock::Now() - start_time;
  return response;
}

} // namespace pggate
} // namespace yb
