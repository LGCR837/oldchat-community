package com.im.oldchat.ui;

import android.support.v7.app.AlertDialog;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.ResourceSection;

import org.json.JSONObject;

class ResourceSectionsActivitySupport2 extends ResourceSectionsActivitySupport {
    protected boolean canDeleteSection(ResourceSection section) {
        if (section == null || myUid == null || myUid.isEmpty()) {
            return false;
        }
        if (section.is_owner) {
            return true;
        }
        return myUid.equals(section.owner_uid);
    }

    protected void confirmDeleteSection(final ResourceSection section) {
        new AlertDialog.Builder(this)
                .setTitle("删除分区")
                .setMessage("删除后分区内资源将一并移除，是否继续？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteSection(section);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    protected void deleteSection(final ResourceSection section) {
        if (section == null || section.id == null || section.id.isEmpty()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("section_id", section.id);
            HttpUtil.post("/resources/sections/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    sections.remove(section);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(ResourceSectionsActivitySupport2.this, "已删除", Toast.LENGTH_SHORT).show();
                    loadQuota();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(ResourceSectionsActivitySupport2.this, "删除失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }
}
