package cn.chonor.lib;

import java.applet.Applet;

public class Purse extends Applet {     //APDU Object
    private Papdu papdu;                //文件系统

    private KeyFile keyfile;            //密钥文件
    private BinaryFile cardfile;       //应用基本文件
    private BinaryFile personfile;     //持卡人基本文件
    private EPFile EPfile;              //电子钱包文件

    /**
     * 构造函数 注册
     * @param bArray
     * @param bOffset
     * @param bLength
     */
    public Purse(byte[] bArray, short bOffset, byte bLength){
        papdu = new Papdu();
        byte aidLen = bArray[bOffset];
        if(aidLen == (byte)0x00)
            register();
        else
            register(bArray, (short)(bOffset + 1), aidLen);
    }

    /**
     * 安装
     * @param bArray
     * @param bOffset
     * @param bLength
     */
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new Purse(bArray, bOffset, bLength);
    }

    /**
     * 选择是否执行
     * @param apdu
     */
    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }
        //取APDU缓冲区数组引用并将之赋给新建数组
        byte[] buf= apdu.getBuffer();
        //取APDU缓冲区中数据放到变量papdu
        short lc = apdu.setIncomingAndReceive(); //接收数据并存储到数组中
        papdu.cla = buf[ISO7816.OFFSET_CLA];
        papdu.ins = buf[ISO7816.OFFSET_INS];
        papdu.p1 = buf[ISO7816.OFFSET_P1];
        papdu.p2 = buf[ISO7816.OFFSET_P2];
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, papdu.pdata, (short)0, lc);
        //判断命令APDU是否包含数据段，有数据则获取数据长度，并对le赋值，否则，即不需要lc和data，则获取缓冲区原本lc实际上是le
        boolean hasData = (papdu.APDUContainData());
        if(hasData){
            papdu.lc = buf[ISO7816.OFFSET_LC];
            papdu.le = buf[ISO7816.OFFSET_CDATA+lc];
        }
        else{
            papdu.le = buf[ISO7816.OFFSET_LC];
            papdu.lc = 0;
        }

        boolean rc = handleEvent(); //是否成功处理了命令

        //判断是否需要返回数据，并设置apdu缓冲区
        if(rc && papdu.le!=0){
            Util.arrayCopyNonAtomic(papdu.pdata, (short)0, buf, (short)0, (short)papdu.le);
            apdu.setOutgoingAndSend((short)0, (short)papdu.le);
        }
    }

     * 对命令的分析和处理
     * @return 是否成功处理了命令
     */
    private boolean handleEvent(){
        switch(papdu.ins){
            case condef.INS_CREATE_FILE:       return create_file();
            //todo：完成写二进制命令，读二进制命令，写密钥命令
            case condef.INS_WRITE_BIN:		   return write_bin();
            case condef.INS_WRITE_KEY:		   return write_key();
            case condef.INS_READ_BIN:		   return read_bin();
            case condef.INS_GET_SESPK:		   return get_sespk();
            case condef.INS_GET_MAC:		   return get_mac();
            case condef.INS_BAL:               return get_balance();
            case condef.INS_PUR:               return purchase();
            case condef.INS_NIIT_TRANS:
                if(papdu.p1 == (byte)0x00)     return init_load();
                if(papdu.p1 == (byte)0x01)     return init_purchase();
                ISOException.throwIt(ISO7816.SW_WRONG_P1P2);
            case condef.INS_LOAD:              return load();
        }
        ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        return false;
    }
    /**
     * 测试MAC和TAC生成
     * @return
     */
    private boolean get_mac(){

        PenCipher EnCipher;              //数据加解密方式实现
        EnCipher = new PenCipher();

        byte[] key = JCSystem.makeTransientByteArray((short)8, JCSystem.CLEAR_ON_DESELECT);//临时数组
        byte[] data = JCSystem.makeTransientByteArray((short)32, JCSystem.CLEAR_ON_DESELECT);//临时数组
        Util.arrayCopyNonAtomic(papdu.pdata, (short)0, key, (short)0, (short)8);
        Util.arrayCopyNonAtomic(papdu.pdata, (short)8, data, (short)0, (short)16);

        EnCipher.gmac4(key, data, (short)16, papdu.pdata);

        return true;
    }
    /**
     * 测试生成过程密钥
     * @return
     */
    private boolean get_sespk(){
        PenCipher EnCipher;              //数据加解密方式实现
        EnCipher = new PenCipher();

        byte[] key = JCSystem.makeTransientByteArray((short)16, JCSystem.CLEAR_ON_DESELECT);;
        byte[] data = JCSystem.makeTransientByteArray((short)8, JCSystem.CLEAR_ON_DESELECT);;
        Util.arrayCopyNonAtomic(papdu.pdata, (short)0, key, (short)0, (short)16);
        Util.arrayCopyNonAtomic(papdu.pdata, (short)16, data, (short)0, (short)8);

        EnCipher.gen_SESPK(key, data, (short)0, (short)8, papdu.pdata, (short)0);

        return true;
    }

    /**
     * 修改二进制文件
     * @return
     */
    private boolean write_bin(){
        if(papdu.cla != (byte)0x00)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 == (byte)0x17) //持卡人基本文件
            personfile.write_bineary(papdu.p2, papdu.lc, papdu.pdata);
        else if(papdu.p1 == (byte)0x16) //应用基本文件
            cardfile.write_bineary(papdu.p2, papdu.lc, papdu.pdata);
        else
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        return true;
    }

    /**
     * 增加或修改密钥
     * @return
     */
    private boolean write_key(){
        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x00)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(papdu.lc != (byte)0x15)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if(keyfile == null)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        keyfile.addkey(papdu.p2, papdu.lc, papdu.pdata);

        return true;
    }

    /**
     * 读取二进制文件
     * @return
     */
    private boolean read_bin(){
        if(papdu.cla != (byte)0x00)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 == (byte)0x17) //持卡人基本信息
            personfile.read_binary(papdu.p2, papdu.le, papdu.pdata);
        else if(papdu.p1 == (byte)0x16) //应用基本文件
            cardfile.read_binary(papdu.p2, papdu.le, papdu.pdata);
        else
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        return true;
    }

    /**
     * 创建文件
     * @return
     */
    private boolean create_file() {
        switch(papdu.pdata[0]){
            case condef.EP_FILE:        return EP_file(); //电子钱包文件
            //todo:完成创建密钥文件，持卡人基本文件和应用基本文件
            case condef.KEY_FILE:		return Key_file(); //密钥文件
            case condef.CARD_FILE:		return CARD_file(); //应用基本文件
            case condef.PERSON_FILE:	return PERSON_file(); //持卡人基本文件
            default:
                ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
        }
        return true;
    }
    /**
     * 创建电子钱包文件
     * @return
     */
    private boolean EP_file() {
        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x00 || papdu.p2 != (byte)0x18)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(papdu.lc != (byte)0x07)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if(EPfile != null)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        EPfile = new EPFile(keyfile);

        return true;
    }
    /**
     * 创建密钥文件
     * @return
     */
    private boolean Key_file() {
        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x00 || papdu.p2 != (byte)0x00)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(papdu.lc != (byte)0x07)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if(keyfile != null)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        keyfile = new KeyFile();

        return true;
    }
    /**
     * 创建应用基本文件
     * @return
     */
    private boolean CARD_file() {
        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x00 || papdu.p2 != (byte)0x16)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(papdu.lc != (byte)0x07)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if(cardfile != null)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        cardfile = new BinaryFile(papdu.pdata);

        return true;
    }

    /**
     * 持卡人基本文件
     * @return
     */
    private boolean PERSON_file() {
        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x00 || papdu.p2 != (byte)0x17)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(papdu.lc != (byte)0x07)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if(personfile != null)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        personfile = new BinaryFile(papdu.pdata);

        return true;
    }

    /**
     * 圈存命令的实现
     * @return
     */
    private boolean load() {
        short rc;

        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x00 && papdu.p2 != (byte)0x00)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(EPfile == null)
            ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);

        if(papdu.lc != (short)0x0B)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        rc = EPfile.load(papdu.pdata);

        if(rc == 1)
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        else if(rc == 2)
            ISOException.throwIt(condef.SW_LOAD_FULL);
        else if(rc == 3)
            ISOException.throwIt(ISO7816.SW_RECORD_NOT_FOUND);

        papdu.le = (short)4;

        return true;
    }

    /**
     * 圈存初始化命令的实现
     * @return
     */
    private boolean init_load() {
        short num,rc;

        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x00 && papdu.p2 != (byte)0x02)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(papdu.lc != (short)0x0B)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if(EPfile == null)
            ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);

        num = keyfile.findkey(papdu.pdata[0]);

        if(num == 0x00)
            ISOException.throwIt(ISO7816.SW_RECORD_NOT_FOUND);

        rc = EPfile.init4load(num, papdu.pdata);

        if(rc == 2)
            ISOException.throwIt((condef.SW_LOAD_FULL));

        papdu.le = (short)0x10;

        return true;
    }

    /**
     * 消费命令的实现
     * @return
     */
    private boolean purchase(){
        short rc;

        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x01 && papdu.p2 != (byte)0x00)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(EPfile == null)
            ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);

        if(papdu.lc != (short)0x0F)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        rc = EPfile.purchase(papdu.pdata);//返回：0 命令执行成功； 1 MAC校验错误 2 消费超额； 3 密钥未找到

        if(rc == 1)
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        else if(rc == 2)
            ISOException.throwIt((condef.SW_BAL_NOT_ENOUGH));
        else if(rc == 3)
            ISOException.throwIt(ISO7816.SW_RECORD_NOT_FOUND);

        papdu.le = (short)8;

        return true;
    }

    /**
     * 余额查询功能的实现
     * @return
     */
    private boolean get_balance(){
        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x01 && papdu.p2 != (byte)0x02)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        short result;
        byte[] balance = JCSystem.makeTransientByteArray((short)4, JCSystem.CLEAR_ON_DESELECT);
        result = EPfile.get_balance(balance);

        if(result == (short)0)
            Util.arrayCopyNonAtomic(balance, (short)0, papdu.pdata, (short)0, (short)4);//返回电子钱包余额

        papdu.le = (short)0x04;

        return true;
    }

    /**
     * 消费初始化的实现
     * @return
     */
    private boolean init_purchase(){
        short num,rc;

        if(papdu.cla != (byte)0x80)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        if(papdu.p1 != (byte)0x01 && papdu.p2 != (byte)0x02)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        if(papdu.lc != (short)0x0B)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if(EPfile == null)
            ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);

        num = keyfile.findkey(papdu.pdata[0]);//根据tag寻找密钥返回密钥的记录号
        if(num == 0x00)//找不到相应密钥
            ISOException.throwIt(ISO7816.SW_RECORD_NOT_FOUND);

        rc = EPfile.init4purchase(num, papdu.pdata);//返回0表示成功,返回2表示余额不足
        if(rc == 2)
            ISOException.throwIt((condef.SW_BAL_NOT_ENOUGH));

        papdu.le = (short)15;

        return true;
    }
}
