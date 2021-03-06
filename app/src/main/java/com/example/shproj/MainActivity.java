package com.example.shproj;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    public static final String URL_HOST = "https://shproj2020.herokuapp.com/";

    static Reservation[] reservations;
    static Room[] rooms;
    static RoomType[] roomTypes;
    static Teacher[] teachers;
    static Map<Integer, Teacher> teachersMap; //prsId -> teacher
    static Map<String, Integer> nameToIndex; // roomName -> index in rooms
    static Map<Integer, String> roomTypesMap; // typeId -> typeDescription
    static String schedule;

    PageFragment[] fragments;
    TextView[] daysTV;

    ViewPager pager;

    int day, dayOfWeek;

    final String[] daysStrings = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    final static long oneDay = 86400000;
    final static int THRESHOLD_AHEAD = 60;

    // todo      swipe for weeks
    // todo      addActivity check for events in the same time and room
    // todo      roomDialog доделать фильтр по типам, по количеству мест, по времени
    // todo      changeRoomDialog disable roomNumber changing
    // todo      threshold for previous days
    // todo      roomType changing
    // todo      adminActivity таймлайн кабинета

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences pref = getSharedPreferences("pref", 0);
        pref.edit().putString("cookie", "").apply();

//        binding = ActivityMainBinding.inflate(getLayoutInflater());
        String login = pref.getString("login", ""),
                pw = pref.getString("password", "");

        if(!login.equals(""))
            new Thread(() -> {
                String cookie = connect("login?username=" + login + "&password=" + pw, null);
                int errorCount = pref.getInt("errorCount", 0),
                        count = pref.getInt("requestCount", 0);
                if(cookie.length() < 5) {
                    if(cookie.length() == 4 && Integer.parseInt(cookie.substring(1)) < 500) {
                        runOnUiThread(() -> {
                                Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show();
                                invalidateOptionsMenu();
                        });
                        pref.edit().putString("login", "").putString("cookie", "").apply();
                    } else {
                        errorCount++;
                        runOnUiThread(() ->
                                Toast.makeText(this, "Проблемы с подключением к серверу", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    pref.edit().putString("cookie", cookie).apply();
                }
                count++;
                log("error percent: " + String.format(Locale.getDefault(), "%.2f%%", (double) errorCount/count*100));
                pref.edit().putInt("errorCount", errorCount).putInt("requestCount", count).apply();
            }).start();

        new Thread(() -> refreshEverything(this)).start();


        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        final long EPOCH = 1577826000000L; // 01/01/2020

        int daysFromBeginning = (int) ((calendar.getTimeInMillis() - EPOCH)/oneDay); // days from 01/01/2020
        log("days from 01/01/2020: " + daysFromBeginning);

        fragments = new PageFragment[daysFromBeginning + THRESHOLD_AHEAD];
        long date = EPOCH;
        for (int i = 0; i < fragments.length; i++) {
            fragments[i] = new PageFragment();
            calendar = Calendar.getInstance();
            calendar.setTimeInMillis(date);
            date += oneDay;
//            calendar.add(Calendar.DAY_OF_MONTH, i);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            fragments[i].c = calendar;
        }

        day = daysFromBeginning;
        pager = findViewById(R.id.pager);
        if(pager.getAdapter() == null) {
            MyFragmentPagerAdapter pagerAdapter = new MyFragmentPagerAdapter(getSupportFragmentManager());
            pager.setAdapter(pagerAdapter);
        }
        pager.setCurrentItem(day);
        pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                dayOfWeek = fragments[position].c.get(Calendar.DAY_OF_WEEK)-2;
                if(dayOfWeek == -1)
                    dayOfWeek = 6;
                day = position;
//                log(day + ", dayofweek: " + dayOfWeek);
                makeDays(daysTV, dayOfWeek);
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });
        daysTV = new TextView[7];
        calendar = Calendar.getInstance();
        dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)-2;
        if(dayOfWeek == -1)
            dayOfWeek = 6;
        makeDays(daysTV, dayOfWeek);
    }

    static void refreshEverything(Activity activity) {
        try {
            String s = connect("get_rooms_list", null);
            if (s.length() < 5 && !s.replaceAll(" ", "").equals("[]")) {
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, "Проблемы с подключением к серверу", Toast.LENGTH_SHORT).show());
                return;
            }
            JSONArray array = new JSONArray(s), classTypes;
            rooms = new Room[array.length()];
            nameToIndex = new HashMap<>();
            JSONObject obj;
            for (int i = 0; i < array.length(); i++) {
                rooms[i] = new Room();
                obj = array.getJSONObject(i);

                rooms[i].classNumber = obj.getString("classNumber");
                rooms[i].seats = obj.getInt("seats");
                rooms[i].responsible = obj.getInt("responsible");
                classTypes = obj.getJSONArray("classTypes");
                rooms[i].classTypes = new int[classTypes.length()];
                for (int j = 0; j < classTypes.length(); j++) {
                    rooms[i].classTypes[j] = classTypes.getInt(j);
                }

            }
            Arrays.sort(rooms, (r1, r2) -> String.CASE_INSENSITIVE_ORDER.compare(r1.classNumber, r2.classNumber));
            for (int i = 0; i < rooms.length; i++) {
                rooms[i].id = i;
                nameToIndex.put(rooms[i].classNumber, i);
            }

            s = connect("get_room_types_list", null);
            array = new JSONArray(s);
            roomTypes = new RoomType[array.length()];
            roomTypesMap = new HashMap<>();
            for (int i = 0; i < array.length(); i++) {
                RoomType type = new RoomType();
                type.typeId = array.getJSONObject(i).getInt("typeId");
                type.description = array.getJSONObject(i).getString("typeDescription");
                roomTypes[i] = type;
                roomTypesMap.put(type.typeId, type.description);
            }

            long start = System.currentTimeMillis() - THRESHOLD_AHEAD * oneDay; // 30 days
            long end = System.currentTimeMillis() + THRESHOLD_AHEAD * oneDay;
            schedule = connect("schedule?startTime=" + start + "&endTime=" + end,
                    null);

            s = connect("get_teacher_list", null);

            JSONArray tchr = new JSONArray(s);
            teachersMap = new HashMap<>();
            teachers = new Teacher[tchr.length()];
            for (int i = 0; i < tchr.length(); i++) {
                obj = tchr.getJSONObject(i);
                Teacher teacher = new Teacher();
                teacher.admin = obj.getBoolean("admin");
                teacher.fio = obj.getString("fio");
                teacher.personId = obj.getInt("prsId");
                teacher.info = obj.getString("anotherInfo");
                teachersMap.put(teacher.personId, teacher);
                teachers[i] = teacher;
            }

            if(activity instanceof MainActivity)
                ((MainActivity) activity).refreshSchedule();

            for (Room room : rooms) {
                room.typeDescriptions = new String[room.classTypes.length];
                for (int i = 0; i < room.classTypes.length; i++) {
                    room.typeDescriptions[i] = roomTypesMap.get(room.classTypes[i]);
                }
                room.teacherResponsible = teachersMap.get(room.responsible);
            }
        } catch (Exception e) {loge(e);}
    }

    void refreshSchedule() {
        try {
            JSONArray array = new JSONArray(schedule);
            JSONObject obj;

            reservations = new Reservation[array.length()];
            for (int i = 0; i < reservations.length; i++) {
                obj = array.getJSONObject(i);
                reservations[i] = new Reservation();
                reservations[i].classNumber = obj.getString("classNumber");
                reservations[i].customerId = obj.getInt("customerId");
                reservations[i].startTime = obj.getLong("startTime");
                reservations[i].endTime = obj.getLong("endTime");
                reservations[i].reason = obj.getString("reason");
                reservations[i].reservationId = obj.getInt("reservationId");
                reservations[i].teacherId = obj.getInt("teacherId");
            }
            for (Reservation reservation : reservations) {
                Object object = nameToIndex.get(reservation.classNumber);
                reservation.room = rooms[(int) object];
            }
            runOnUiThread(() -> {
                for (PageFragment fragment : fragments) {
                    LinkedList<Reservation> list = new LinkedList<>();
                    long fragmentTime = fragment.c.getTimeInMillis();
                    for (Reservation reservation : reservations) {
                        if (reservation.startTime >= fragmentTime && reservation.startTime < fragmentTime + oneDay
                                || reservation.startTime < fragmentTime && reservation.endTime > fragmentTime) {
                            list.add(reservation);
                        }
                    }

                    fragment.list = list.toArray(new Reservation[0]);
                    fragment.draw();
                }
            });
        } catch (Exception e) {
            loge(e);
        }
    }

    void makeDays(TextView[] tv, int selected) { // RAR 1.5.1 legacy: okras()
        for (int i = 0; i < 7; i++) {
            if(tv[i] == null) {
                tv[i] = new TextView(this);
                tv[i].setId(i);
                tv[i].setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                p.weight = (float) 1 / 7;
                tv[i].setLayoutParams(p);
                final int finalI = i;
                tv[i].setOnClickListener(v -> {
                    day -= (dayOfWeek - finalI);
                    dayOfWeek = finalI;
                    pager.setCurrentItem(day);
                });
                LinearLayout linear1 = findViewById(R.id.days);
                linear1.setWeightSum(1);
                linear1.addView(tv[i]);
            }
            ForegroundColorSpan color;

            if(i == selected){
                tv[i].setBackground(getDrawable(R.drawable.day_cell));
                tv[i].setTextColor(Color.parseColor("#38423B"));
                color = new ForegroundColorSpan(Color.parseColor("#38423B"));
            } else {
                tv[i].setBackground(null);
                tv[i].setTextColor(Color.BLACK);
                color = new ForegroundColorSpan(Color.BLACK);
            }

            String s;
            if(day + i >= dayOfWeek && day + i - dayOfWeek < fragments.length)
                s = daysStrings[i] + "\n" + fragments[day - dayOfWeek + i].c.get(Calendar.DAY_OF_MONTH);
            else
                s = " \n  ";
            Spannable spans = new SpannableString(s);
            spans.setSpan(new RelativeSizeSpan(1.3f), 0, s.indexOf("\n"), Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            spans.setSpan(color, 0, s.indexOf("\n"), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spans.setSpan(new RelativeSizeSpan(1.2f), s.indexOf("\n"), s.length(), Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            spans.setSpan(color, s.indexOf("\n"), s.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv[i].setText(spans);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if(getSharedPreferences("pref", MODE_PRIVATE).getString("login", "").equals(""))
            menu.add(0, 0, 0, "Логин").setIcon(R.drawable.login)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        else {
            menu.add(0, 1, 0, "Забронировать").setIcon(getDrawable(R.drawable.add)).
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, 3, 0, "Кабинеты").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            menu.add(0, 4, 0, "Учителя").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            menu.add(0, 2, 0, "Выйти из аккаунта")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 0:
                startActivityForResult(new Intent(this, LoginActivity.class), 0);
                break;
            case 1:
                if (teachersMap != null)
                    startActivityForResult(new Intent(this, AddActivity.class), 1);
                else
                    Toast.makeText(this, "Подождите...", Toast.LENGTH_SHORT).show();
                break;
            case 2:
                getSharedPreferences("pref", 0).edit().putString("login", "").putString("cookie", "").apply();
                invalidateOptionsMenu();
                break;
            case 3:
                if (teachersMap != null) {
                    Intent intent = new Intent(this, AdminActivity.class);
                    intent.putExtra("type", 0);
                    startActivity(intent);
                } else
                    Toast.makeText(this, "Подождите...", Toast.LENGTH_SHORT).show();
                break;
            case 4:
                if(teachersMap != null) {
                    Intent intent = new Intent(this, AdminActivity.class);
                    intent.putExtra("type", 1);
                    startActivity(intent);
                } else
                    Toast.makeText(this, "Подождите...", Toast.LENGTH_SHORT).show();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if(requestCode == 0 && resultCode == 1) {
            invalidateOptionsMenu();
        }
        if(requestCode == 1 && resultCode == 1) {
            new Thread(() -> {
                long start = System.currentTimeMillis() - THRESHOLD_AHEAD*oneDay; // 30 days
                long end = System.currentTimeMillis() + THRESHOLD_AHEAD*oneDay;
                schedule = connect("schedule?startTime=" + start + "&endTime=" + end,
                        null);
                if(schedule.length() < 5) {
                    Toast.makeText(this, "Проблемы с подключением к серверу", Toast.LENGTH_SHORT).show();
                } else
                    refreshSchedule();
            }).start();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    class MyFragmentPagerAdapter extends FragmentPagerAdapter {

        MyFragmentPagerAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            return fragments[position];
        }

        @Override
        public int getCount() {
            return fragments.length;
        }
    }

    static class Reservation {
        int reservationId, teacherId, customerId;
        String classNumber, reason;
        Room room;
        long startTime, endTime;
    }
    static class Room {
        int id, seats, responsible;
        String classNumber;
        Teacher teacherResponsible;
        int[] classTypes;
        String[] typeDescriptions;
    }
    static class RoomType {
        int typeId;
        String description;
    }
    static class Teacher {
        int personId;
        String fio, info;
        boolean admin;
    }

    static String connect(String url, String query) {
        return connect(url, query, false, null);
    }
    static String connect(String url, String query, String cookie) {
        return connect(url, query, false, cookie);
    }
    static String connect(String url, String query, boolean ignore, String cookie) {
        url = URL_HOST + url;
        if(!ignore)
            log("connect " + url + ", query: " + query);
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            if(cookie != null) {
                con.setRequestProperty("Cookie", cookie);
            }
            if (query == null) {
                con.setRequestMethod("GET");
                con.connect();
            } else {
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.getOutputStream().write(query.getBytes());
                con.connect();
            }
            if(con.getResponseCode() != 200) {
                loge(url.replaceAll(URL_HOST, "") + " connect failed, code " + con.getResponseCode() + ", message: " + con.getResponseMessage());
//                log(url);
//                log("query: '" + query + "'");
                return "/" + con.getResponseCode();
            }
            if(url.contains("login")) {
                Map<String, List<String>> a = con.getHeaderFields();
                if(a.containsKey("Set-Cookie")) {
                    System.out.println(a.get("Set-Cookie").get(0));
                    return a.get("Set-Cookie").get(0).split(";")[0];
                }
            }
            if(con.getInputStream() != null) {
                BufferedReader rd = new BufferedReader(new InputStreamReader(con.getInputStream()));
                String line;
                StringBuilder result = new StringBuilder();
                while ((line = rd.readLine()) != null) {
                    result.append(line);
                }
                rd.close();
                if(!ignore)
                    log("connect result: " + result.toString());
                return result.toString();
            } else
                return "";
        } catch (Exception e) {
            loge(e);
            return "//";
        }
    }
    static <T> void log(T msg) { if(msg != null) Log.v("mylog", msg.toString()); else loge("null log");}
    static <T> void loge(T msg) {
        if(msg instanceof Exception)
            ((Exception) msg).printStackTrace();
        if(msg != null) Log.e("mylog", msg.toString()); else loge("null log");
    }
}
