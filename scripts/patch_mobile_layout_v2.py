from pathlib import Path
p=Path('app/src/main/res/layout/fragment_search_mobile.xml'); s=p.read_text()
def r(a,b):
 global s
 assert a in s,a[:120]
 s=s.replace(a,b,1)
att='''            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="4dp"
                android:layout_marginTop="10dp"
                android:layout_marginBottom="4dp"
                android:text="Attore"
'''
row='''            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:orientation="horizontal">
                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="6dp"
                    android:layout_weight="1"
                    android:orientation="vertical">
                    <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginStart="4dp" android:layout_marginBottom="4dp" android:text="Ordina per" android:textColor="#A9B2C2" android:textSize="12sp" />
                    <FrameLayout android:layout_width="match_parent" android:layout_height="46dp" android:background="@drawable/bg_filter_control_tv">
                        <Spinner android:id="@+id/sp_sort" android:layout_width="match_parent" android:layout_height="match_parent" android:background="@android:color/transparent" android:paddingEnd="30dp" android:spinnerMode="dropdown" />
                        <ImageView android:layout_width="18dp" android:layout_height="18dp" android:layout_gravity="end|center_vertical" android:layout_marginEnd="9dp" android:src="@drawable/ic_filter_chevron_down" tools:ignore="ContentDescription" />
                    </FrameLayout>
                </LinearLayout>
                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="6dp"
                    android:layout_weight="1"
                    android:orientation="vertical">
                    <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginStart="4dp" android:layout_marginBottom="4dp" android:text="Piattaforma" android:textColor="#A9B2C2" android:textSize="12sp" />
                    <FrameLayout android:layout_width="match_parent" android:layout_height="46dp" android:background="@drawable/bg_filter_control_tv">
                        <Spinner android:id="@+id/sp_platform" android:layout_width="match_parent" android:layout_height="match_parent" android:background="@android:color/transparent" android:paddingEnd="30dp" android:spinnerMode="dropdown" />
                        <ImageView android:layout_width="18dp" android:layout_height="18dp" android:layout_gravity="end|center_vertical" android:layout_marginEnd="9dp" android:src="@drawable/ic_filter_chevron_down" tools:ignore="ContentDescription" />
                    </FrameLayout>
                </LinearLayout>
            </LinearLayout>

'''
r(att,row+att)
actor='''            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/rv_actor_suggestions"
                android:layout_width="match_parent"
                android:layout_height="126dp"
                android:layout_marginTop="8dp"
                android:clipChildren="false"
                android:clipToPadding="false"
                android:paddingStart="2dp"
                android:paddingEnd="2dp"
                android:visibility="gone"
                tools:listitem="@layout/item_people_mobile" />
'''
director='''
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="4dp"
                android:layout_marginTop="10dp"
                android:layout_marginBottom="4dp"
                android:text="Regista"
                android:textColor="#A9B2C2"
                android:textSize="12sp" />
            <EditText
                android:id="@+id/et_director_search"
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:background="@drawable/bg_filter_control_tv"
                android:hint="Scrivi il nome del regista..."
                android:imeOptions="actionSearch"
                android:inputType="textPersonName"
                android:maxLines="1"
                android:paddingStart="14dp"
                android:paddingEnd="14dp"
                android:textColor="#FFFFFF"
                android:textColorHint="#80FFFFFF"
                android:textSize="14sp"
                tools:ignore="Autofill" />
            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/rv_director_suggestions"
                android:layout_width="match_parent"
                android:layout_height="126dp"
                android:layout_marginTop="8dp"
                android:clipChildren="false"
                android:clipToPadding="false"
                android:paddingStart="2dp"
                android:paddingEnd="2dp"
                android:visibility="gone"
                tools:listitem="@layout/item_people_mobile" />
'''
r(actor,actor+director)
p.write_text(s)
print('Mobile layout patched')
