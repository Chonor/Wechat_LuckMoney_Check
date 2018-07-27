package cn.chonor.wechatcheck;

/**
 * Created by chonor on 2018/5/29.
 */

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.HashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;



public class Tests implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (loadPackageParam.packageName.equals("com.tencent.mm")) {
            final HashMap<String,Long> redBagIdMap = new HashMap<>();
            XposedBridge.log("loaded: " + loadPackageParam.packageName);
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {//这里是hook回调函数
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            super.afterHookedMethod(param);
                            ClassLoader cl = ((Context) param.args[0]).getClassLoader();
                            XposedHelpers.findAndHookMethod(
                                    "com.tencent.wcdb.database.SQLiteDatabase",
                                    cl,
                                    "insert",
                                    String.class, String.class, ContentValues.class,
                                    new XC_MethodHook() {
                                        @Override
                                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                            if("message".equals(param.args[0]) && param.args[1].equals("msgId")){
                                                ContentValues cv = (ContentValues)param.args[2];
                                                String content = cv.getAsString("content");
                                                XposedBridge.log("信息内容为:"+content);
                                                long createTime = cv.getAsLong("createTime");
                                                XposedBridge.log("信息创建时间为:"+createTime);
                                                if(!TextUtils.isEmpty(content)){
                                                    // 解析xml获取paymsgid
                                                    int startIdx = content.indexOf("<paymsgid>") ;
                                                    String payMsgId="";
                                                    if(startIdx!=-1) {
                                                        XposedBridge.log("收到的信息为红包信息");
                                                        payMsgId = content.substring(startIdx+19,startIdx+19+31);
                                                        XposedBridge.log("payMsgId:"+payMsgId);
                                                    }
                                                    if(!TextUtils.isEmpty(payMsgId)){
                                                        String redBagId = payMsgId.substring(18);
                                                        XposedBridge.log("红包Id为:"+redBagId);
                                                        redBagIdMap.put(redBagId,createTime);
                                                        XposedBridge.log("redBagId:"+redBagId+",createTime:"+createTime);
                                                    }

                                                }
                                            }
                                            super.afterHookedMethod(param);
                                        }
                                    });
                            XposedHelpers.findAndHookMethod(
                                    "com.tencent.mm.plugin.luckymoney.ui.i",
                                    cl,
                                    "getView",
                                    int.class, View.class, ViewGroup.class,
                                    new XC_MethodHook() {
                                        @Override
                                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {//获取信息
                                            Object obj = param.thisObject;
                                            Method method = obj.getClass().getDeclaredMethod("sJ", new Class[]{Integer.TYPE});
                                            method.setAccessible(true);
                                            obj = method.invoke(obj, new Object[]{((Integer) param.args[0]).intValue()});
                                            Object owJ = obj.getClass().getField("owJ").get(obj);
                                            Object oxe = obj.getClass().getField("oxe").get(obj);
                                            Object oxf = obj.getClass().getField("oxf").get(obj);
                                            Object oxr = obj.getClass().getField("oxr").get(obj);
                                            Object oxs = obj.getClass().getField("oxs").get(obj);
                                            Object oxt = obj.getClass().getField("oxt").get(obj);
                                            Object oxu = obj.getClass().getField("oxu").get(obj);
                                            Object userName = obj.getClass().getField("userName").get(obj);
                                            XposedBridge.log("获取领取红包列表中的\""+oxr+"\"领取红包的信息");//打印提示
                                            XposedBridge.log("payMsgId为:"+owJ
                                                    +"；红包钱数为:"+oxe+"分；时间戳为:"+oxf
                                                    +"；留言为:"+oxt+"；是否手气最佳:"+oxu);
                                            String payMsgId = (String)owJ ;
                                            String redBagId = payMsgId.substring(18) ;
                                            XposedBridge.log("redBagId:"+redBagId);
                                            long time = Long.parseLong((String)oxf)*1000 ;
                                            XposedBridge.log(userName+"领取红包的时间戳为:"+time);
                                            long costTime = -1 ;
                                            if(redBagIdMap.containsKey(redBagId)){
                                                long redBagCreateTime = redBagIdMap.get(redBagId);
                                                costTime = time-redBagCreateTime ;
                                            }
                                            XposedBridge.log(userName+"领取红包的耗时为"+costTime);

                                            Object result = param.getResult();
                                            if(result instanceof LinearLayout){//修改界面，显示抢红包时间
                                                LinearLayout itemView = (LinearLayout)result;
                                                LinearLayout leftLayout = (LinearLayout)itemView.getChildAt(1);
                                                TextView timeText = (TextView)leftLayout.getChildAt(3);
                                                if ( costTime!=-1 ){
                                                    timeText.setTextColor(0xFFFF0000);
                                                    timeText.setTextSize(12);
                                                    TextPaint paint = timeText.getPaint();
                                                    paint.setFakeBoldText(true);
                                                    timeText.setText("用时小于"+(costTime/1000.0)+"s"+((costTime/1000.0)<2.5?" 可能使用了外挂":""));
                                                }

                                            }
                                            super.afterHookedMethod(param);
                                        }
                                    });
                        }

                    }
            );
        }
    }
}

