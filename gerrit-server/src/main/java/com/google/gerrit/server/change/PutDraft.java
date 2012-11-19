// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.change;

import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.DefaultInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.reviewdb.client.PatchLineComment;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.change.GetDraft.Side;
import com.google.gerrit.server.change.PutDraft.Input;
import com.google.inject.Inject;
import com.google.inject.Provider;

import java.sql.Timestamp;
import java.util.Collections;

class PutDraft implements RestModifyView<DraftResource, Input> {
  static class Input {
    String kind;
    String id;
    String path;
    Side side;
    Integer line;
    Timestamp updated; // Accepted but ignored.

    @DefaultInput
    String message;
  }

  private final Provider<ReviewDb> db;
  private final Provider<DeleteDraft> delete;

  @Inject
  PutDraft(Provider<ReviewDb> db, Provider<DeleteDraft> delete) {
    this.db = db;
    this.delete = delete;
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Object apply(DraftResource rsrc, Input in)
      throws AuthException, BadRequestException, ResourceConflictException,
      Exception {
    if (in == null || in.message == null || in.message.trim().isEmpty()) {
      return delete.get().apply(rsrc, null);
    } else if (in.kind != null && !"gerritcodereview#comment".equals(in.kind)) {
      throw new BadRequestException("expected kind gerritcodereview#comment");
    } else if (in.line != null && in.line < 0) {
      throw new BadRequestException("line must be >= 0");
    }

    PatchLineComment c = rsrc.getComment();
    if (in.path != null
        && !in.path.equals(c.getKey().getParentKey().getFileName())) {
      // Updating the path alters the primary key, which isn't possible.
      // Delete then recreate the comment instead of an update.
      db.get().patchComments().delete(Collections.singleton(c));
      c = update(new PatchLineComment(
          new PatchLineComment.Key(
              new Patch.Key(rsrc.getPatchSet().getId(), in.path),
              c.getKey().get()),
          c.getLine(),
          rsrc.getAuthorId(),
          c.getParentUuid()), in);
      db.get().patchComments().insert(Collections.singleton(c));
    } else {
      db.get().patchComments().update(Collections.singleton(update(c, in)));
    }
    return new GetDraft.Comment(c);
  }

  private PatchLineComment update(PatchLineComment e, Input in) {
    if (in.side != null) {
      e.setSide(in.side == GetDraft.Side.PARENT ? (short) 0 : (short) 1);
    }
    if (in.line != null) {
      e.setLine(in.line);
    }
    e.setMessage(in.message.trim());
    e.updated();
    return e;
  }
}
