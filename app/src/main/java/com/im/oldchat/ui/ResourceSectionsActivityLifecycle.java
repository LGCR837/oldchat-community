package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;

import com.im.oldchat.R;
import com.im.oldchat.models.ResourceSection;

class ResourceSectionsActivityLifecycle extends ResourceSectionsActivitySupport2 {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resource_sections);

        findViewByIdCompat(R.id.btnResourceSectionsBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        View btnAdd = findViewByIdCompat(R.id.btnResourceSectionsAdd);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showQuotaCreateSectionDialog();
                }
            });
        }

        pbLoading = findViewByIdCompat(R.id.pbResourceSectionsLoading);
        tvQuota = findViewByIdCompat(R.id.tvResourceQuota);
        lvSections = findViewByIdCompat(R.id.lvResourceSections);

        adapter = new ResourceSectionAdapter(this, sections);
        if (lvSections != null) {
            lvSections.setAdapter(adapter);
            lvSections.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    if (position >= 0 && position < sections.size()) {
                        openSection(sections.get(position));
                    }
                }
            });
            lvSections.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                    if (position >= 0 && position < sections.size()) {
                        ResourceSection s = sections.get(position);
                        if (canDeleteSection(s)) {
                            confirmDeleteSection(s);
                            return true;
                        }
                    }
                    return false;
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        myUid = prefs.getString("my_uid", "");
        loadQuota();
        loadSections();
    }
}
