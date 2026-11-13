/**
*
**/

module QNEdge
{
	const string VERSION = "1.0.0.1";
	sequence<byte> SEQImage;

    enum EAIAlgorithmType
    {
        PersonDetect,//人员检测-
        SafeHatDetect,//安全帽检测-
		SafeGearDetect,//安全防护服检测-
        NumberOfPeopleDetect, //人员数量统计
        BeltFlowDetect,//皮带流量检测-
        BeltDeviationDetect,//皮带偏离检测-
		BeltExceptionalObjectDetect,//皮带异常物体检测-
		FaceDetect,//人脸检测-
		FaceReg,//人脸识别-
		InstrumentData,//仪器数据
		FireOrSmoke,//火灾烟雾检测-
		BeltCrackSegDetect,//皮带裂纹分割检测-
		HumanFlowDetect,//人员流量统计-
		VehicleDetect,//车辆检测-
		MotorcycleDetect,//摩托车检测-
		AnimalDetect,//动物检测-
		HumanPhoneDetect,//人员打电话-
		HumanSmokingDetect,//人员吸烟-
		HumanFallDetect,//人员摔倒-
		HumanFightDetect,//人员打斗-
		HumanLeavePostionDetect,//离岗-
		HumanSleepInPostionDetect,//睡岗-
		HumanWanderDetect,//徘徊-
		TunnelDeformationDetect,//隧道变形检测
		PersonNoExistDetect,//场景没有人检测
		FaceMaskDetect,//口罩检测
		LaneDetect,//占道检测
		SwimmingDetect,//游泳检测
		FishingDetect,//钓鱼检测
		FloatingObjectsInWater,//水体漂浮物检测
		WaterPollutionDetect,//水体污染检测
		MountainFloodOrMudslide,//山洪泥石流检测
		LandslideDetect,//滑坡检测
		TrafficCongestion,//交通拥堵检测
		StrawWasteIncineration,//桔梗焚烧检测
		IllegalSewageDischarge,//非法排污检测
		ShipDetect,//船舶检测
		AlgaeBloomDetect,//水草泛滥检测
		UnauthorizedConstructioDetect,//违章建筑检测
		RegionNoExistPersonDetect,//区域无人检测
		FireControlAlarm,//消防控制主机报警
		Other
    };

	enum EDirection
	{
		Left,
		Right,
		Up,
		Down
	};
	//消防控制设备运行模式
	enum EFireControlMode
	{
		auto,//自动
		manual//手动
	};

	struct SEvent
	{
		string				strID;
		EAIAlgorithmType	eat;
		bool				bCommon;
		long				lAbnormalCount;
		string				strDeviceID;
		string				strPosition;
		string				strName;
		string				strType;
		string				strIP;
		string				strCreateDTime;
		string				strDesc;
		string				strImgBase64;
		string				strEventSnapURL;
		long				lCommonMin;
	};
	//预检测场景
	enum EAIPreDetect
	{
		BeltRect,
		Road,
		HumanPose,
		FireControl,
		Null
	};
	//编码类型
	enum ECodecType
	{
		H264,
		H265
	};
	//错误代码
	enum EErrorCode
	{
		EECSuccess,
		EECFail,
		EECDeviceNone,
		EECMoreMaxAlgCount,
		EECUserNameError,
		EECPwdError,
		EECMoreMaxDecode,
		EECCoderType,
		EECMoreRes,
		EECOther
	};
	//算法服务状态
	enum EAlgServerStatus
	{
		EASSReadyed,
		EASSReadying
	};
	//目标检测对象
	struct SObject
	{
		string strID;
		string strClassName;
		string strScore;
		int nTop;
		int nLeft;
		int nWidth;
		int nHeight;
		SEQImage img;
	};
	//矩形框
	struct SRectangle
	{
		int nX;
		int nY;
		int nWidth;
		int nHeight;
	};

	//点
	struct SPoint
	{
		int nX;
		int nY;
	};
	sequence<SPoint> SEQPoint;//点数组
	//线段
	struct SLineSeg
	{
		SPoint p1;
		SPoint p2;
	};
	sequence<SEQPoint> SEQROI;//线段数组

	//算法配置
	struct SAlgConfig
	{
		string				strDeviceID;
		bool				bEnable;
		EAIAlgorithmType	eat;
		string				strAlgName;
		int					nInterval;//间隔帧数量
		int					nMinScore;//最小阈值0-100
		int					nParam;//整形参数
		string				strParam;//字符型参数
		int					nContinuousImageCount;//连续图片数量
		int					nAlarmIntervalSecond;//2次报警间隔时间
		SEQROI				seqROI;//不规则感兴趣区域数组
		SLineSeg			slsLeft;//左边线段
		SLineSeg			slsRight;//右边线段
		SLineSeg			slsLine;//线段
	};
	
	sequence<SObject>SEQObject;
	sequence<SEvent>SEQEvent;	
	sequence<SAlgConfig>SEQAlg;

	//设备结构
	struct SDevice
	{
		string strID;
		string strPos;
		string strRtspUrl;
		string strIP;
		string strPort;
		string strName;
		string strDescription;
		string strUserName;
		string strPassword;
		SEQAlg seqAlgConfig;
		EAIPreDetect eaipd;
		ECodecType	ect;
		int		nServerID;
	};
	sequence<SDevice>SEQDevice;

	//设备性能指标
	struct	SPerformance
	{
		string		strCPU;
		string		strMem;
		string		strNetwork;
		string		strDisk;
	};

	sequence<string>SEQEventReceiveURL;

	//配置信息
	struct	SConfig
	{
		//事件接受URL地址
		SEQEventReceiveURL  seqURL;
	};
	sequence<string>SEQButtonText;
	sequence<SPoint>SEQButtonCoordinates;

	//事件列表操作接口
	interface IQNEEvent
	{
		SEQEvent				GetEventList(string strDeviceID,int nPage);
		EErrorCode				AddEvent(SEvent se);
		EErrorCode				DeleteEvent(SEvent se);
		EErrorCode				UpdateEvent(SEvent se);
	};

	//控制机械手臂操作接口
	interface IQNEArmControl
	{
		EErrorCode				MoveTask(EDirection ed,int nSpeed);//控制机械手臂移动
		EErrorCode				ClickTask(SEQButtonText seqButtonText,SEQButtonCoordinates seqCoordinates);//控制机械手臂点击某按钮或某批按钮
		EErrorCode				TurnKey(EFireControlMode efcm);//控制机械手臂拧手自动钥匙
		SPoint					SimulationClickGetCoordinates(string strButtonText);//模拟点击某按钮获取真实坐标
	};

	//提交消防控制主机信息
	interface IQNEFCHInfo
	{
		EErrorCode				CorrectButtonCoordinates(string strButtonText,SPoint spNew);//修正按钮坐标
		
	};

	//独立算法调用接口
	interface IQNEAlgHandle
	{
		EErrorCode			AlgImageHandle(SDevice sd,SEQImage seqImg,SAlgConfig sac);
		EErrorCode			StartAllDeviceALG(SEQDevice sds);
		EErrorCode			StopALLDeviceALG();
		EErrorCode			StartSpecialDeviceALG(SDevice sd);
		EErrorCode			StopSpecialDeviceALG(string strDeviceID);
	};

	//其他配置操作接口
	interface IQNEConfig
	{
		EErrorCode				Config(SConfig sc);
	};
};
