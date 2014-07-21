package me.shumei.open.oks.baidutieba;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;

import me.shumei.open.oks.baidutieba.tools.MD5;

import org.json.JSONObject;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;

/**
 * 使签到类继承CommonData，以方便使用一些公共配置信息
 * 
 * @author wolforce
 * 
 */
public class Signin extends CommonData {
    String resultFlag = "false";
    String resultStr = "未知错误！";

    Context context;
    String cfg;

    /**
     * <p>
     * <b>程序的签到入口</b>
     * </p>
     * <p>
     * 在签到时，此函数会被《一键签到》调用，调用结束后本函数须返回长度为2的一维String数组。程序根据此数组来判断签到是否成功
     * </p>
     * 
     * @param ctx
     *            主程序执行签到的Service的Context，可以用此Context来发送广播
     * @param isAutoSign
     *            当前程序是否处于定时自动签到状态<br />
     *            true代表处于定时自动签到，false代表手动打开软件签到<br />
     *            一般在定时自动签到状态时，遇到验证码需要自动跳过
     * @param cfg
     *            “配置”栏内输入的数据
     * @param user
     *            用户名
     * @param pwd
     *            解密后的明文密码
     * @return 长度为2的一维String数组<br />
     *         String[0]的取值范围限定为两个："true"和"false"，前者表示签到成功，后者表示签到失败<br />
     *         String[1]表示返回的成功或出错信息
     */
    public String[] start(Context ctx, boolean isAutoSign, String cfg, String user, String pwd) {
        // 把主程序的Context传送给验证码操作类，此语句在显示验证码前必须至少调用一次
        CaptchaUtil.context = ctx;
        // 标识当前的程序是否处于自动签到状态，只有执行此操作才能在定时自动签到时跳过验证码
        CaptchaUtil.isAutoSign = isAutoSign;

        this.context = ctx;
        this.cfg = cfg;

        try {
            // 存放Cookies的HashMap
            HashMap<String, String> cookies = new HashMap<String, String>();
            // Jsoup的Response
            Response res;

            // 获取自定义配置View里设定的百度登录方式的值，默认使用Android登录
            int baidulogintype = 1;
            try {
                JSONObject jsonObj = new JSONObject(cfg);
                baidulogintype = jsonObj.getInt(ManageTask.KEY_LOGIN_TYPE);
            } catch (Exception e) {
                e.printStackTrace();
            }
            // 根据设定的方式登录，获取Cookies
            switch (baidulogintype) {
                case 0:
                    cookies = BaiduLoginMethod.loginBaiduWeb(user, pwd);
                    break;
                case 1:
                    cookies = BaiduLoginMethod.loginBaiduAndroid(user, pwd);
                    break;
                case 2:
                    cookies = BaiduLoginMethod.loginBaiduWap(user, pwd);
                    break;
            }
            // 判断是否登录成功
            String customLoginErrorType = cookies.get(BaiduLoginMethod.CUSTOM_COOKIES_KEY);
            if (customLoginErrorType.equals(BaiduLoginMethod.ERROR_LOGIN_SUCCEED)) {
                // 登录成功，把标记用的Cookies删除掉
                cookies.remove(BaiduLoginMethod.CUSTOM_COOKIES_KEY);
                // 执行贴吧签到函数
                resultStr = signEachTieba(cookies);
            } else {
                // 登录失败，直接跳出签到函数
                if (customLoginErrorType.equals(BaiduLoginMethod.ERROR_ACCOUNT_INFO)) {
                    resultFlag = "false";
                    resultStr = "登录失败，有可能是账号或密码错误";
                } else if (customLoginErrorType.equals(BaiduLoginMethod.ERROR_CANCEL_CAPTCHA)) {
                    resultFlag = "false";
                    resultStr = "用户取消输入验证码";
                } else if (customLoginErrorType.equals(BaiduLoginMethod.ERROR_DOWN_CAPTCHA)) {
                    resultFlag = "false";
                    resultStr = "拉取验证码错误";
                } else if (customLoginErrorType.equals(BaiduLoginMethod.ERROR_INPUT_CAPTCHA)) {
                    resultFlag = "false";
                    resultStr = "输入的验证码错误";
                }
                return new String[] { resultFlag, resultStr };
            }

        } catch (IOException e) {
            this.resultFlag = "false";
            this.resultStr = "连接超时";
            e.printStackTrace();
        } catch (Exception e) {
            this.resultFlag = "false";
            this.resultStr = "未知错误！";
            e.printStackTrace();
        }

        return new String[] { resultFlag, resultStr };
    }

    /**
     * 循环签到各个贴吧
     * 
     * @param cookies
     */
    private String signEachTieba(HashMap<String, String> cookies) {
        String baseUrl = "http://tieba.baidu.com/f?kw=";
        String tiebaInfoUrl;
        int succeedNum = 0;
        int failedNum = 0;
        int signedNum = 0;// 已经签到的个数

        boolean signintoast = true;// 是否开启进度提示
        int intervalBaseTime = 6;// 基础间隔时间
        int intervalRandTime = 9;// 随机浮动时间
        String tiebaListStr = "";

        try {
            JSONObject cfgJsonObj = new JSONObject(cfg);
            signintoast = cfgJsonObj.getBoolean(ManageTask.KEY_SIGNIN_TOAST);
            intervalBaseTime = cfgJsonObj.getInt(ManageTask.KEY_INTERVAL_BASE);
            intervalRandTime = cfgJsonObj.getInt(ManageTask.KEY_INTERVAL_RAND);
            tiebaListStr = cfgJsonObj.getString(ManageTask.KEY_TIEBA_LIST);
        } catch (Exception e) {
            e.printStackTrace();
        }

        ArrayList<String> tiebaList = getTiebaList(tiebaListStr, cookies);
        if (tiebaList.size() == 0) {
            resultFlag = "false";
            return "没有检测到任何贴吧，请执行以下操作：\n1.检查网络连接是否正常，是否在使用cmwap连接（失败率较高）\n2.在配置栏内输入需要签到的贴吧，并用中文或英文逗号隔开，如:李毅，wow，仙剑";
        }

        Response res;
        StringBuilder sb = new StringBuilder();
        for (String tiebaName : tiebaList) {
            // 每个贴吧前面都要加个信息，用StringBuilder可增加效率，避免有多个任务时直接用加号会导致的性能下降
            sb.append(tiebaName);
            sb.append("吧:");
            System.out.println("签到" + tiebaName);

            // 如果签到失败就进行重试
            StringBuilder sbTemp = null;
            boolean isSignSucceed = false;// 是否签到成功
            boolean isSkip = false;// 是否跳过本贴吧
            for (int i = 0; i < RETRY_TIMES; i++) {
                isSkip = false;
                isSignSucceed = false;
                sbTemp = new StringBuilder();// 单个贴吧的StringBuilder
                try {
                    // 访问贴吧信息URL
                    // {"no":0,"error":"","data":{"user_info":{"user_id":774751123,"is_sign_in":0,"user_sign_rank":0,"sign_time":0,"cont_sign_num":0,"cout_total_sing_num":0,"is_org_disabled":0},"forum_info":{"is_on":true,"is_filter":false,"forum_info":{"forum_id":3995,"level_1_dir_name":"\u5355\u673a\u6e38\u620f"},"current_rank_info":{"sign_count":2298,"sign_rank":19,"member_count":83137,"dir_rate":"0.1"},"yesterday_rank_info":{"sign_count":2617,"sign_rank":18,"member_count":82861,"dir_rate":"0.1"},"weekly_rank_info":{"sign_count":2585,"sign_rank":19,"member_count":78709},"monthly_rank_info":{"sign_count":2472,"sign_rank":17,"member_count":73105},"level_1_dir_name":"\u6e38\u620f","level_2_dir_name":"\u5355\u673a\u6e38\u620f"}}}
                    tiebaInfoUrl = "http://tieba.baidu.com/sign/info?kw=" + URLEncoder.encode(tiebaName, "GBK");
                    res = Jsoup.connect(tiebaInfoUrl).cookies(cookies).userAgent(UA_ANDROID).referrer(baseUrl).timeout(TIME_OUT).ignoreContentType(true).method(Method.GET).execute();
                    cookies.putAll(res.cookies());
//                    if (res.body().contains("\"is_on\":false")) {
//                        sbTemp.append("无需签到(跳过)\n");
//                        // resultFlag = "true";
//                        isSignSucceed = true;
//                        isSkip = true;
//                    } else
                    if (res.body().contains("\"is_sign_in\":1")) {
                        sbTemp.append("今日已签(跳过)\n");
                        // resultFlag = "true";
                        isSignSucceed = true;
                        isSkip = true;
                    } else {
                        res = this.submitSignTieba(cookies.get("BDUSS"), getFid(), tiebaName, getIMEI(this.context));
                        if (res.statusCode() == 200) {
                            String[] analyseResultArr = this.analyseResult(res.body());
                            System.out.println(analyseResultArr[1]);
                            if (analyseResultArr[0].equals("true")) {
                                isSignSucceed = true;
                                sbTemp.append(analyseResultArr[1] + "\n");
                            }
                        }
                    }

                } catch (IOException e) {
                    isSignSucceed = false;
                    sbTemp.append("连接失败\n");
                    e.printStackTrace();
                } catch (Exception e) {
                    isSignSucceed = false;
                    sbTemp.append("未知错误！\n");
                    e.printStackTrace();
                }

                // 如果签到成功就跳出重试循环
                if (isSignSucceed) {
                    succeedNum++;
                    break;
                }
            }

            int intervalTime;
            if (isSkip)
                intervalTime = 0;
            else
                intervalTime = (int) (intervalBaseTime + Math.random() * intervalRandTime);
            // 拼接单个贴吧的签到记录
            StringBuilder sigleTiebaSB = new StringBuilder();
            sigleTiebaSB.append("(");
            sigleTiebaSB.append(intervalTime);
            sigleTiebaSB.append("s↓)");
            sigleTiebaSB.append(sbTemp);

            // 把单个贴吧的签到记录追加到最终输出的StringBuilder上
            sb.append(sigleTiebaSB);

            // 向主程序发送Toast广播，显示签到进度
            if (signintoast) {
                signedNum++;
                StringBuilder msgSB = new StringBuilder();
                msgSB.append(signedNum);
                msgSB.append("/");
                msgSB.append(tiebaList.size());
                msgSB.append(" ");
                msgSB.append(tiebaName);
                if (isSignSucceed) {
                    msgSB.append(" 成功");
                } else {
                    msgSB.append(" 失败");
                }
                sendShowToastBC(context, msgSB.toString(), true);
            }

            // 签到完一个任务后随机停顿一段时间再去签到下一个任务，以防万一
            try {
                Thread.sleep(1000 * intervalTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        failedNum = tiebaList.size() - succeedNum;
        String signinStatistic = "成功" + succeedNum + "个，失败" + failedNum + "个\n\n";
        // 只要有一个贴吧签到错误，那整个任务就算作失败
        if (failedNum > 0) {
            resultFlag = "false";
        } else {
            resultFlag = "true";
        }
        return signinStatistic + sb.toString();
    }
    
    /**
     * 获取fid
     * @return
     */
    private String getFid() {
        int fid = (int) (Math.random() * 100000) + 10000;
        return fid + "";
    }
    
    /**
     * 获取tbs
     * @param BDUSS BDUSS
     * @return 返回有效的tbs
     */
    private String getTbs(String BDUSS) {
        String tbs = null;
        String tbsUrl = "http://tieba.baidu.com/dc/common/tbs";
        for (int i = 0; i < RETRY_TIMES; i++) {
            try {
                Response res = Jsoup.connect(tbsUrl).cookie("BDUSS", BDUSS).userAgent(UA_BAIDU_PC).timeout(TIME_OUT).ignoreContentType(true).method(Method.GET).execute();
                JSONObject jsonObj = new JSONObject(res.body());
                tbs = jsonObj.optString("tbs");
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (tbs != null && tbs.length() > 0) break;
        }
        return tbs;
    }
    
    /**
     * 分析签到的结果
     * @param str 签到的结果字符串
     * @return String[0]=>成功或失败的标记，String[1]=>详细信息
     */
    private String[] analyseResult(String str) {
        String returnFlag = "false";
        String returnStr = "未知错误";
        try {
            JSONObject jsonObj = new JSONObject(str);
            int error_code = jsonObj.optInt("error_code");
            if (error_code == 0) {
                returnFlag = "true";
                JSONObject user_info_obj = jsonObj.optJSONObject("user_info");
                String cont_sign_num = user_info_obj.optString("cont_sign_num");
                String sign_bonus_point = user_info_obj.optString("sign_bonus_point");
                returnStr = "签到成功," + cont_sign_num + "天," + "+" + sign_bonus_point;
            } else if (error_code == 160002) {
                returnFlag = "true";
                returnStr = "已经签过";
            } else {
                returnStr = jsonObj.optString("error_msg");
            }
        } catch (Exception e) {
        }
        return new String[]{returnFlag, returnStr};
    }
    
    /**
     * 根据传入的LinkedHashMap计算sign签名
     * @param postDatas
     */
    private String getSign(LinkedHashMap<String, String> postDatas)
    {
        StringBuilder sb = new StringBuilder();
        for (Iterator it = postDatas.keySet().iterator(); it.hasNext();) {
            Object key = it.next();
            sb.append(key + "=" + postDatas.get(key));
        }
        sb.append("tiebaclient!!!");
        String sign = MD5.md5(sb.toString());
        return sign;
    }
    
    /**
     * 提交单一贴吧签到
     * @param BDUSS BDUSS
     * @param tieba_id 贴吧ID
     * @param tieba_name 贴吧名
     * @param imei IMEI
     * @return 返回签到的Response
     */
    public Response submitSignTieba(String BDUSS, String tieba_id, String tieba_name, String imei)
    {
        String signUrl = "http://c.tieba.baidu.com/c/c/forum/sign";
        LinkedHashMap<String, String> postDatas = new LinkedHashMap<String, String>();
        postDatas.put("BDUSS", BDUSS);
        postDatas.put("_phone_imei", imei);
        postDatas.put("fid", tieba_id);
        postDatas.put("from", "baidu_appstore");
        postDatas.put("kw", tieba_name);
        postDatas.put("model", "sdk");
        postDatas.put("net_type", "1");
        postDatas.put("stErrorNums", "0");
        postDatas.put("stMethod", "2");
        postDatas.put("stMode", "3");
        postDatas.put("stSize", "64");
        postDatas.put("stTime", "1234");
        postDatas.put("stTimesNum", "0");
        postDatas.put("tbs", getTbs(BDUSS));
        postDatas.put("timestamp", System.currentTimeMillis() + "");
        postDatas.put("sign", getSign(postDatas));
        Response res = null;
        try {
            res = Jsoup.connect(signUrl).data(postDatas).userAgent(UA_BAIDU_ANDROID).timeout(TIME_OUT).ignoreContentType(true).method(Method.POST).execute();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return res;
    }

    /**
     * 获取贴吧名字的List
     * 
     * @param cfgStr
     * @param cookies
     * @return
     */
    private ArrayList<String> getTiebaList(String cfgStr, HashMap<String, String> cookies) {
        ArrayList<String> cfgNameList = new ArrayList<String>();// 设置的贴吧名
        String tiebaStr = cfgStr.replace("，", ",");// 把所有中文逗号替换成英文逗号
        if (tiebaStr.length() > 0) {
            String[] tiebaArr = tiebaStr.split(",");
            for (String tiebaName : tiebaArr) {
                cfgNameList.add(tiebaName);
            }
        }
        return cfgNameList;
    }

    /**
     * 获取当前手机的网络连接方式
     * 
     * @param context
     * @return 返回“WIFI”或“GPRS”字符串
     */
    public static String getNetworkType(Context context) {
        String netType = "NONE";
        try {
            ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connManager.getActiveNetworkInfo();
            if (netInfo != null) {
                if (netInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                    netType = "WIFI";
                } else if (netInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                    netType = "GPRS";
                } else {
                    netType = "NONE";
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return netType;
    }

    /**
     * 获取手机的IMEI
     * 
     * @return String
     */
    public static String getIMEI(Context context) {
        String imei = "456156451534587";// 随机串
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            imei = telephonyManager.getDeviceId();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return imei;
    }

}
